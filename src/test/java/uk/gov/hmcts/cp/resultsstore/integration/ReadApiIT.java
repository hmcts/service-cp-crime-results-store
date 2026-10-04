package uk.gov.hmcts.cp.resultsstore.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static uk.gov.hmcts.cp.resultsstore.support.UsersGroupsStub.NO_GROUP_USER;
import static uk.gov.hmcts.cp.resultsstore.support.UsersGroupsStub.SECOND_LINE_USER;
import static uk.gov.hmcts.cp.resultsstore.support.UsersGroupsStub.SYSTEM_USER;
import static uk.gov.hmcts.cp.resultsstore.support.UsersGroupsStub.USER_ID_HEADER;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.resultsstore.application.ExtractionSweep;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractor;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcReceiptStore;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcShareStore;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;
import uk.gov.hmcts.cp.resultsstore.support.UsersGroupsStub;

/**
 * The read API end to end (US1 to US7): a real server, authorisation on with usersgroups stubbed, shares stored
 * through the store over the context's database. The intake timeouts are short (transaction 2 s, statement 1 s,
 * lock 500 ms, idle-in-transaction 1 s), so the derived visibility lag is 5 s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "authz.http.enabled=true",
    "resultsstore.intake.store.transaction-timeout=2s",
    "resultsstore.intake.store.statement-timeout=1s",
    "resultsstore.intake.store.lock-timeout=500ms",
    "resultsstore.intake.store.idle-in-transaction-timeout=1s",
    "resultsstore.read.statement-timeout=1s"
})
@ActiveProfiles("test")
@DisplayName("read API end to end")
class ReadApiIT {

    private static final Duration LAG = Duration.ofSeconds(5);

    private static final Duration WITHIN = Duration.ofSeconds(30);

    private static final String DAY = "2026-10-02";

    private static final String SHARES = "/results-store/v1/shares";

    private static final String UNEXPECTED_REASON = "UNEXPECTED:IllegalStateException";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final UsersGroupsStub USERSGROUPS = UsersGroupsStub.start();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meters;

    private JdbcReceiptStore receipts;

    private JdbcShareStore store;

    private UUID hearingId;

    private int messages;

    @DynamicPropertySource
    static void identityAndStore(final DynamicPropertyRegistry registry) {
        registry.add("authz.http.identity-url-template", USERSGROUPS::identityUrl);
        PostgresTestSupport.register(registry);
    }

    @BeforeEach
    void emptyTables() {
        jdbc.sql("TRUNCATE event_receipt, share_defendant, hearing_share_payload, hearing_share, hearing_day_head")
                .update();
        receipts = new JdbcReceiptStore(jdbc, new TransactionTemplate(transactionManager));
        store = new JdbcShareStore(jdbc, new TransactionTemplate(transactionManager), receipts,
                JdbcShareStore.Timeouts.DEFAULTS);
        hearingId = UUID.randomUUID();
    }

    private UUID stored(final String sharedTime) {
        return stored(SampleShares.share(hearingId, DAY, sharedTime), null);
    }

    private UUID stored(final String text, final Projection projection) {
        messages++;
        final String messageId = "ID:read-" + messages;
        receipts.recordArrival(SampleShares.arrival(messageId, text));
        final StoreRequest request = SampleShares.request(messageId, text);
        store.store(projection == null ? request : new StoreRequest(request.messageId(), request.identity(),
                request.shareId(), request.sharedDays(), request.checksum(), request.text(), projection));
        return request.shareId();
    }

    private UUID storedFailed(final String sharedTime) {
        return stored(SampleShares.share(hearingId, DAY, sharedTime, null, ""),
                new Projection.Failed(UNEXPECTED_REASON, ExtractionFailureKind.UNEXPECTED));
    }

    private HttpResponse<byte[]> get(final String path, final Map<String, String> headers)
            throws IOException, InterruptedException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        headers.forEach(request::header);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode json(final String path, final String user) throws IOException, InterruptedException {
        final HttpResponse<byte[]> response = get(path, Map.of(USER_ID_HEADER, user));
        assertThat(response.statusCode()).as("status").isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private JsonNode pull(final String query) throws IOException, InterruptedException {
        return json(SHARES + "?" + query, SYSTEM_USER);
    }

    private static List<String> shareIds(final JsonNode page) {
        return page.get("items").valueStream().map(item -> item.get("shareId").asString()).toList();
    }

    private void awaitTheLag() {
        // Every share stored so far is older than the lag once the database's clock has moved past it.
        final Instant cut = jdbc.sql("SELECT clock_timestamp()").query(Instant.class).single().plus(LAG);
        await().atMost(WITHIN).until(() -> jdbc.sql("SELECT clock_timestamp()").query(Instant.class).single()
                .isAfter(cut.plusMillis(200)));
    }

    /** A concrete path for each route, over the share stored by the test. */
    private String path(final ApiRoute route, final UUID shareId) {
        return switch (route) {
            case PULL_SHARES -> SHARES + "?storedAfterSeq=0";
            case SEARCH_SHARES -> SHARES + "?courtCentreId=" + SampleShares.COURT_CENTRE + "&sharedDayFrom=" + DAY
                    + "&sharedDayTo=" + DAY;
            case GET_SHARE -> SHARES + "/" + shareId;
            case GET_SHARE_PAYLOAD -> SHARES + "/" + shareId + "/payload";
            case LIST_HEARING_DAY_SHARES -> "/results-store/v1/hearings/" + hearingId + "/days/" + DAY + "/shares";
            case GET_SHARE_ARRIVED_PAYLOAD -> SHARES + "/" + shareId + "/payload/arrived";
        };
    }

    private static void assertBounded(final HttpResponse<byte[]> response, final int status, final String reason)
            throws IOException {
        assertThat(response.statusCode()).isEqualTo(status);
        final JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "reason");
        assertThat(body.get("reason").asString()).isEqualTo(reason);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .doesNotContain("results-store/v1");
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void each_endpoint_should_serve_a_system_users_caller(final ApiRoute route) throws Exception {
        final UUID shareId = stored("2026-10-02T09:00:00Z");

        final HttpResponse<byte[]> response = get(path(route, shareId), Map.of(USER_ID_HEADER, SYSTEM_USER));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void each_endpoint_should_serve_a_second_line_support_caller(final ApiRoute route) throws Exception {
        final UUID shareId = stored("2026-10-02T09:00:00Z");

        assertThat(get(path(route, shareId), Map.of(USER_ID_HEADER, SECOND_LINE_USER)).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void each_endpoint_should_refuse_a_caller_in_neither_group_403_with_a_bounded_body(final ApiRoute route)
            throws Exception {
        final UUID shareId = stored("2026-10-02T09:00:00Z");

        final HttpResponse<byte[]> response = get(path(route, shareId), Map.of(USER_ID_HEADER, NO_GROUP_USER));

        assertBounded(response, 403, "forbidden");
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .doesNotContain(shareId.toString());
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void each_endpoint_should_refuse_no_identity_401_with_a_bounded_body(final ApiRoute route) throws Exception {
        assertBounded(get(path(route, stored("2026-10-02T09:00:00Z")), Map.of()), 401, "unauthenticated");
    }

    static Stream<Arguments> spoofs() {
        final List<Map<String, String>> spoofs = List.of(
                Map.of("CPP-ACTION", "results-store.get-share-payload"),
                Map.of("CPP-ACTION", "results-store.anything"),
                Map.of("Content-Type", "application/vnd.results-store.get-share-payload+json"),
                Map.of("Accept", "application/vnd.results-store.get-share-payload+json"),
                Map.of("Accept", "application/vnd.usersgroups.get-logged-in-user-permissions+json"));
        return Stream.of(ApiRoute.values()).flatMap(route -> spoofs.stream().map(spoof -> Arguments.of(route, spoof)));
    }

    @ParameterizedTest
    @MethodSource("spoofs")
    void a_spoofed_cpp_action_content_type_or_accept_should_change_no_outcome(final ApiRoute route,
            final Map<String, String> spoof) throws Exception {
        final UUID shareId = stored("2026-10-02T09:00:00Z");
        for (final String user : List.of(SYSTEM_USER, NO_GROUP_USER)) {
            final Map<String, String> plain = Map.of(USER_ID_HEADER, user);
            final Map<String, String> spoofed = new HashMap<>(spoof);
            spoofed.put(USER_ID_HEADER, user);

            assertThat(get(path(route, shareId), spoofed).statusCode()).as(user)
                    .isEqualTo(get(path(route, shareId), plain).statusCode());
        }
    }

    @Test
    void pull_should_not_return_a_share_until_the_lag_has_passed() throws Exception {
        final UUID shareId = stored("2026-10-02T09:00:00Z");

        final JsonNode early = pull("storedAfterSeq=0");

        assertThat(shareIds(early)).isEmpty();
        assertThat(early.get("nextStoredAfterSeq").asLong()).isZero();
        await().atMost(WITHIN).pollInterval(Duration.ofMillis(250))
                .until(() -> shareIds(pull("storedAfterSeq=0")).contains(shareId.toString()));
    }

    @Test
    void pull_paged_to_the_end_should_present_every_share_once_in_order() throws Exception {
        final List<String> expected = new ArrayList<>();
        for (int hour = 9; hour < 14; hour++) {
            expected.add(stored("2026-10-02T%02d:00:00Z".formatted(hour)).toString());
        }
        awaitTheLag();

        final List<String> presented = new ArrayList<>();
        long cursor = 0;
        boolean more = true;
        while (more) {
            final JsonNode page = pull("storedAfterSeq=" + cursor + "&limit=2");
            presented.addAll(shareIds(page));
            cursor = page.get("nextStoredAfterSeq").asLong();
            more = page.get("hasMore").asBoolean();
        }

        assertThat(presented).containsExactlyElementsOf(expected);
        assertThat(shareIds(pull("storedAfterSeq=" + cursor))).isEmpty();
    }

    @Test
    void a_filtered_pull_should_advance_the_cursor_over_non_matching_ranges() throws Exception {
        stored("2026-10-02T09:00:00Z");
        stored("2026-10-02T10:00:00Z");
        awaitTheLag();
        final long highest = jdbc.sql("SELECT max(stored_seq) FROM hearing_share").query(Long.class).single();

        final JsonNode youth = pull("storedAfterSeq=0&dayYouthSeen=true");
        final JsonNode otherCourt = pull("storedAfterSeq=0&courtCentreId=" + UUID.randomUUID());

        assertThat(shareIds(youth)).isEmpty();
        assertThat(youth.get("hasMore").asBoolean()).isFalse();
        assertThat(youth.get("nextStoredAfterSeq").asLong()).isEqualTo(highest);
        assertThat(otherCourt.get("nextStoredAfterSeq").asLong()).isEqualTo(highest);
    }

    @Test
    void visible_up_to_should_be_present_and_behind_the_database_clock_by_the_lag() throws Exception {
        final Instant before = jdbc.sql("SELECT now()").query(Instant.class).single();
        final JsonNode page = pull("storedAfterSeq=0");
        final Instant after = jdbc.sql("SELECT now()").query(Instant.class).single();

        final String visibleUpTo = page.get("visibleUpTo").asString();

        assertThat(visibleUpTo).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(Instant.parse(visibleUpTo)).isBetween(before.minus(LAG), after.minus(LAG));
    }

    @Test
    void a_failed_share_presented_by_a_not_false_pull_should_show_its_key_details_on_re_read_after_the_sweep_fixes_it_and_not_be_re_presented()
            throws Exception {
        final UUID shareId = storedFailed("2026-10-02T09:00:00Z");
        awaitTheLag();

        final JsonNode first = pull("storedAfterSeq=0&dayYouthSeen=notFalse");
        final JsonNode item = first.get("items").get(0);
        assertThat(item.get("shareId").asString()).isEqualTo(shareId.toString());
        assertThat(item.get("projectionStatus").asString()).isEqualTo("FAILED");
        assertThat(item.get("keyDetails").isNull()).isTrue();
        assertThat(item.get("dayYouthSeen").isNull()).isTrue();

        assertThat(sweep().runRound()).containsExactly(SweepRowOutcome.FIXED);

        final JsonNode reread = json(SHARES + "/" + shareId, SYSTEM_USER);
        assertThat(reread.get("projectionStatus").asString()).isEqualTo("OK");
        assertThat(reread.get("keyDetails").get("courtCentreId").asString())
                .isEqualTo(SampleShares.COURT_CENTRE.toString());
        assertThat(shareIds(pull("storedAfterSeq=" + first.get("nextStoredAfterSeq").asLong()
                + "&dayYouthSeen=notFalse"))).isEmpty();
    }

    @Test
    void a_court_pull_should_never_present_a_failed_share_even_after_the_sweep_fills_its_court() throws Exception {
        final UUID shareId = storedFailed("2026-10-02T09:00:00Z");
        awaitTheLag();
        final String court = "courtCentreId=" + SampleShares.COURT_CENTRE;

        final JsonNode before = pull("storedAfterSeq=0&" + court);
        assertThat(shareIds(before)).isEmpty();
        assertThat(sweep().runRound()).containsExactly(SweepRowOutcome.FIXED);

        final JsonNode after = pull("storedAfterSeq=" + before.get("nextStoredAfterSeq").asLong() + "&" + court);

        assertThat(shareIds(after)).doesNotContain(shareId.toString()).isEmpty();
        // Filters are read at read time: from an older cursor (reconciliation) the share now matches.
        assertThat(shareIds(pull("storedAfterSeq=0&" + court))).containsExactly(shareId.toString());
    }

    @Test
    void search_by_day_form_and_by_time_form_should_serve_both_groups() throws Exception {
        final UUID early = stored("2026-10-02T09:00:00Z");
        final UUID late = stored("2026-10-02T18:30:00Z");
        final String court = SHARES + "?courtCentreId=" + SampleShares.COURT_CENTRE;

        for (final String user : List.of(SYSTEM_USER, SECOND_LINE_USER)) {
            assertThat(shareIds(json(court + "&sharedDayFrom=" + DAY + "&sharedDayTo=" + DAY, user)))
                    .containsExactly(early.toString(), late.toString());
            assertThat(shareIds(json(court + "&sharedFrom=2026-10-01T23:00:00Z&sharedTo=2026-10-02T17:00:00Z",
                    user))).containsExactly(early.toString());
        }
    }

    @Test
    void a_share_between_midnight_and_one_bst_should_be_searched_on_its_london_day() throws Exception {
        // 23:30 UTC on 2 October is 00:30 BST on 3 October: the two days differ.
        final UUID share = stored(SampleShares.share(hearingId, "2026-10-03", "2026-10-02T23:30:00Z"), null);
        final String court = SHARES + "?courtCentreId=" + SampleShares.COURT_CENTRE;

        final JsonNode third = json(court + "&sharedDayFrom=2026-10-03&sharedDayTo=2026-10-03", SYSTEM_USER);
        assertThat(shareIds(third)).containsExactly(share.toString());
        assertThat(third.get("items").get(0).get("sharedDayLondon").asString()).isEqualTo("2026-10-03");
        assertThat(third.get("items").get(0).get("sharedDayUtc").asString()).isEqualTo("2026-10-02");
        assertThat(shareIds(json(court + "&sharedDayFrom=" + DAY + "&sharedDayTo=" + DAY, SYSTEM_USER))).isEmpty();
    }

    @Test
    void each_day_of_a_multi_day_hearing_should_list_only_its_own_versions() throws Exception {
        final UUID firstDayEarly = stored("2026-10-02T09:00:00Z");
        final UUID firstDayLate = stored("2026-10-02T18:30:00Z");
        final UUID secondDay = stored(SampleShares.share(hearingId, "2026-10-03", "2026-10-03T10:00:00Z"), null);
        final String days = "/results-store/v1/hearings/" + hearingId + "/days/";

        final JsonNode first = json(days + DAY + "/shares", SYSTEM_USER);
        final JsonNode second = json(days + "2026-10-03/shares", SYSTEM_USER);

        assertThat(shareIds(first)).containsExactly(firstDayEarly.toString(), firstDayLate.toString());
        assertThat(first.get("items").valueStream().map(item -> item.get("versionNumber").asInt()))
                .containsExactly(1, 2);
        assertThat(shareIds(second)).containsExactly(secondDay.toString());
        assertThat(second.get("items").get(0).get("versionNumber").asInt()).isEqualTo(1);
    }

    @Test
    void payload_bytes_should_hash_to_the_etag_and_have_no_metadata_key() throws Exception {
        final UUID workingCopy = stored("2026-10-02T09:00:00Z");
        final UUID arrivedText = stored(SampleShares.share(hearingId, DAY, "2026-10-02T10:00:00Z", "false",
                "a\\u0000b"), null);

        for (final UUID shareId : List.of(workingCopy, arrivedText)) {
            final HttpResponse<byte[]> response = get(SHARES + "/" + shareId + "/payload",
                    Map.of(USER_ID_HEADER, SYSTEM_USER));
            final String etag = response.headers().firstValue("ETag").orElseThrow();
            final String storedChecksum = jdbc.sql("SELECT payload_sha256 FROM hearing_share WHERE share_id = :id").param("id", shareId).query(String.class).single();

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(etag).isEqualTo("\"" + PayloadChecksum.sha256Hex(response.body()) + "\"");
            assertThat(etag).doesNotContain(storedChecksum);
            assertThat(MAPPER.readTree(response.body()).has("_metadata")).isFalse();
            assertThat(MAPPER.readTree(response.body()).has("hearing")).isTrue();
            assertThat(response.headers().firstValue("Content-Length"))
                    .contains(Integer.toString(response.body().length));
            assertThat(response.headers().firstValue("Results-Store-Payload-Form"))
                    .contains(shareId.equals(workingCopy) ? "working-copy" : "arrived-text");
        }
    }

    @Test
    void if_none_match_should_give_304() throws Exception {
        final String path = SHARES + "/" + stored("2026-10-02T09:00:00Z") + "/payload";
        final String etag = get(path, Map.of(USER_ID_HEADER, SYSTEM_USER)).headers().firstValue("ETag").orElseThrow();

        final HttpResponse<byte[]> response = get(path, Map.of(USER_ID_HEADER, SYSTEM_USER, "If-None-Match", etag));

        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().allValues("ETag")).containsExactly(etag);
    }

    /** Phase D: an enriched share, so the working copy and the arrived text differ. */
    private UUID storedEnriched(final String text) {
        messages++;
        final String messageId = "ID:read-" + messages;
        receipts.recordArrival(SampleShares.arrival(messageId, text));
        final StoreRequest request = SampleShares.enrichedRequest(messageId, text,
                SampleShares.finalised("Conditional discharge"));
        store.store(request);
        return request.shareId();
    }

    private String arrivedPath(final UUID shareId) {
        return SHARES + "/" + shareId + "/payload/arrived";
    }

    @Test
    void arrived_should_serve_both_groups_and_refuse_a_caller_in_neither() throws Exception {
        final UUID shareId = stored("2026-10-02T09:00:00Z");

        for (final String user : List.of(SYSTEM_USER, SECOND_LINE_USER)) {
            final HttpResponse<byte[]> response = get(arrivedPath(shareId), Map.of(USER_ID_HEADER, user));
            assertThat(response.statusCode()).as(user).isEqualTo(200);
            assertThat(response.headers().firstValue("Results-Store-Payload-Form")).as(user).contains("arrived-text");
        }
        assertBounded(get(arrivedPath(shareId), Map.of(USER_ID_HEADER, NO_GROUP_USER)), 403, "forbidden");
        assertBounded(get(arrivedPath(shareId), Map.of()), 401, "unauthenticated");
        assertBounded(get(arrivedPath(UUID.randomUUID()), Map.of(USER_ID_HEADER, SYSTEM_USER)), 404,
                "share_not_found");
        assertBounded(get(SHARES + "/1-1-1-1-1/payload/arrived", Map.of(USER_ID_HEADER, SYSTEM_USER)), 400,
                "invalid_share_id");
    }

    @Test
    void arrived_body_parsed_should_equal_the_published_message_without_metadata() throws Exception {
        final String text = SampleShares.shareWithApplication(hearingId, DAY, "2026-10-02T09:00:00Z",
                UUID.randomUUID().toString());
        final UUID shareId = storedEnriched(text);
        final JsonNode published = MAPPER.readTree(text);
        ((ObjectNode) published).remove("_metadata");

        final HttpResponse<byte[]> response = get(arrivedPath(shareId), Map.of(USER_ID_HEADER, SYSTEM_USER));
        assertThat(response.statusCode()).as("status").isEqualTo(200);
        final HttpResponse<byte[]> payload = get(SHARES + "/" + shareId + "/payload",
                Map.of(USER_ID_HEADER, SYSTEM_USER));
        final String etag = response.headers().firstValue("ETag").orElseThrow();
        final String storedChecksum = jdbc.sql("SELECT payload_sha256 FROM hearing_share WHERE share_id = :id")
                .param("id", shareId).query(String.class).single();
        final JsonNode body = MAPPER.readTree(response.body());

        assertThat(body.equals(published)).as("arrived body equals the published message without _metadata")
                .isTrue();
        assertThat(body.has("_metadata")).as("has _metadata").isFalse();
        assertThat(body.get("hearing").get("courtApplications").get(0).has("judicialResults"))
                .as("application results added").isFalse();
        assertThat(MAPPER.readTree(payload.body()).get("hearing").get("courtApplications").get(0)
                .has("judicialResults")).as("the working copy is enriched").isTrue();
        assertThat(etag).isEqualTo("\"" + PayloadChecksum.sha256Hex(response.body()) + "\"");
        assertThat(etag).doesNotContain(storedChecksum);
        assertThat(etag).isNotEqualTo(payload.headers().firstValue("ETag").orElseThrow());
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.headers().firstValue("Content-Length")).contains(Integer.toString(response.body().length));
        assertThat(response.headers().firstValue("Results-Store-Share-Id")).contains(shareId.toString());
        assertThat(response.headers().firstValue("Results-Store-Hearing-Id")).contains(hearingId.toString());
        assertThat(response.headers().firstValue("Results-Store-Hearing-Day")).contains(DAY);
        assertThat(response.headers().firstValue("Results-Store-Shared-Time")).contains("2026-10-02T09:00:00.000000Z");
        assertThat(response.headers().firstValue("Results-Store-Enrichment-Applied")).contains("true");
        assertThat(response.headers().firstValue("Results-Store-Payload-Form")).contains("arrived-text");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void arrived_if_none_match_should_give_304() throws Exception {
        final String path = arrivedPath(stored("2026-10-02T09:00:00Z"));
        final String etag = get(path, Map.of(USER_ID_HEADER, SYSTEM_USER)).headers().firstValue("ETag").orElseThrow();
        final double notModified = requests("arrived_payload", "not_modified");

        final HttpResponse<byte[]> response = get(path, Map.of(USER_ID_HEADER, SYSTEM_USER, "If-None-Match", etag));
        final HttpResponse<byte[]> star = get(path, Map.of(USER_ID_HEADER, SYSTEM_USER, "If-None-Match", "*"));
        final HttpResponse<byte[]> stale = get(path, Map.of(USER_ID_HEADER, SYSTEM_USER, "If-None-Match",
                "\"" + "0".repeat(64) + "\""));

        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().allValues("ETag")).containsExactly(etag);
        assertThat(star.statusCode()).isEqualTo(304);
        assertThat(star.headers().allValues("ETag")).containsExactly(etag);
        assertThat(stale.statusCode()).isEqualTo(200);
        assertThat(requests("arrived_payload", "not_modified")).isEqualTo(notModified + 2);
    }

    @Test
    void a_query_held_by_a_lock_should_give_503_store_unavailable_with_retry_after() throws Exception {
        final UUID shareId = stored("2026-10-02T09:00:00Z");
        final HttpResponse<byte[]> response;
        try (Connection holder = dataSource.getConnection(); Statement lock = holder.createStatement()) {
            holder.setAutoCommit(false);
            lock.execute("LOCK TABLE hearing_share IN ACCESS EXCLUSIVE MODE");
            response = get(SHARES + "/" + shareId, Map.of(USER_ID_HEADER, SYSTEM_USER));
            holder.rollback();
        }

        assertBounded(response, 503, "store_unavailable");
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
    }

    @Test
    void an_unmapped_path_should_be_404_route_not_found_and_counted() throws Exception {
        final double before = meters.get("resultsstore.read.refused").tag("reason", "route_not_found").counter()
                .count();

        assertBounded(get("/results-store/v1/anything", Map.of(USER_ID_HEADER, SYSTEM_USER)), 404,
                "route_not_found");
        assertThat(meters.get("resultsstore.read.refused").tag("reason", "route_not_found").counter().count())
                .isEqualTo(before + 1);
    }

    @Test
    void the_read_meters_should_move_as_contracts_metrics_says() throws Exception {
        final UUID shareId = stored("2026-10-02T09:00:00Z");
        final double shareOk = requests("share", "ok");
        final double shareNotFound = requests("share", "not_found");
        final double payloadNotModified = requests("payload", "not_modified");
        final double pullBadRequest = requests("pull", "bad_request");
        final long pageItems = meters.get("resultsstore.read.page.items").summary().count();
        final long payloadBytes = meters.get("resultsstore.read.payload.bytes").summary().count();
        final long durations = meters.get("resultsstore.read.duration").tag("endpoint", "share").timer().count();

        json(SHARES + "/" + shareId, SYSTEM_USER);
        get(SHARES + "/" + UUID.randomUUID(), Map.of(USER_ID_HEADER, SYSTEM_USER));
        final String etag = get(SHARES + "/" + shareId + "/payload", Map.of(USER_ID_HEADER, SYSTEM_USER)).headers()
                .firstValue("ETag").orElseThrow();
        get(SHARES + "/" + shareId + "/payload", Map.of(USER_ID_HEADER, SYSTEM_USER, "If-None-Match", etag));
        get(SHARES + "?storedAfterSeq=0&limit=0", Map.of(USER_ID_HEADER, SYSTEM_USER));
        pull("storedAfterSeq=0");

        assertThat(requests("share", "ok")).isEqualTo(shareOk + 1);
        assertThat(requests("share", "not_found")).isEqualTo(shareNotFound + 1);
        assertThat(requests("payload", "not_modified")).isEqualTo(payloadNotModified + 1);
        assertThat(requests("pull", "bad_request")).isEqualTo(pullBadRequest + 1);
        assertThat(meters.get("resultsstore.read.page.items").summary().count()).isEqualTo(pageItems + 1);
        assertThat(meters.get("resultsstore.read.payload.bytes").summary().count()).isGreaterThan(payloadBytes);
        assertThat(meters.get("resultsstore.read.duration").tag("endpoint", "share").timer().count())
                .isEqualTo(durations + 2);
    }

    @Test
    void accept_text_html_should_count_one_bad_request_and_no_duration() throws Exception {
        final UUID shareId = stored("2026-10-02T09:00:00Z");
        final double badRequest = requests("share", "bad_request");
        final long durations = meters.get("resultsstore.read.duration").tag("endpoint", "share").timer().count();

        assertBounded(get(SHARES + "/" + shareId, Map.of(USER_ID_HEADER, SYSTEM_USER, "Accept", "text/html")), 406,
                "not_acceptable");

        assertThat(requests("share", "bad_request")).isEqualTo(badRequest + 1);
        assertThat(meters.get("resultsstore.read.duration").tag("endpoint", "share").timer().count())
                .isEqualTo(durations);
    }

    private double requests(final String endpoint, final String outcome) {
        return meters.get("resultsstore.read.requests").tag("endpoint", endpoint).tag("outcome", outcome).counter()
                .count();
    }

    private ExtractionSweep sweep() {
        return new ExtractionSweep(store, new ShareIdentityParser(JsonMapper.builder().build()),
                new KeyDetailsExtractor(), mock(IntakeObserver.class),
                new ExtractionSweep.Settings(KeyDetailsExtractor.EXTRACTOR_VERSION, 3, 100));
    }
}
