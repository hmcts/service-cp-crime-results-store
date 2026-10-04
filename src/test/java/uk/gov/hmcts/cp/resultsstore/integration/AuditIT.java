package uk.gov.hmcts.cp.resultsstore.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.cp.resultsstore.support.UsersGroupsStub.NO_GROUP_USER;
import static uk.gov.hmcts.cp.resultsstore.support.UsersGroupsStub.SYSTEM_USER;
import static uk.gov.hmcts.cp.resultsstore.support.UsersGroupsStub.USER_ID_HEADER;

import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import uk.gov.hmcts.cp.filter.audit.service.AuditPayloadGenerationService;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.filters.PayloadBodyFreeAuditPayloadGenerationService;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcReceiptStore;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcShareStore;
import uk.gov.hmcts.cp.resultsstore.support.EmbeddedBrokerSupport;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;
import uk.gov.hmcts.cp.resultsstore.support.UsersGroupsStub;

/**
 * The audit library as this service runs it (FR-051, FR-052; research R14; D-AUDIT option 4): the HTTP audit filter
 * and its Artemis transport on, the events read from the library's topic on an embedded broker. Each request
 * carries its own {@code CPPCLIENTCORRELATIONID}, which the library copies into both events, so each test reads
 * only its own events.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "authz.http.enabled=true", "audit.http.enabled=true", "cp.audit.enabled=true"
})
@ActiveProfiles("test")
@DisplayName("audit of the read API")
class AuditIT {

    /** The library's audit topic. */
    private static final String AUDIT_TOPIC = "jms.topic.auditing.event";

    private static final String CORRELATION = "CPPCLIENTCORRELATIONID";

    private static final String MARKER = "AUDIT-MARKER-3f9a2c";

    private static final String DAY = "2026-10-02";

    private static final Duration WITHIN = Duration.ofSeconds(20);

    /** How long a test waits to be sure an event it expects not to come has not come. */
    private static final Duration QUIET = Duration.ofSeconds(2);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final UsersGroupsStub USERSGROUPS = UsersGroupsStub.start();

    private static final List<String> PUBLISHED = new CopyOnWriteArrayList<>();

    private static EmbeddedBrokerSupport broker;

    private static ActiveMQConnectionFactory tapFactory;

    private static JMSContext tap;

    private static JMSConsumer tapConsumer;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuditPayloadGenerationService generation;

    private UUID hearingId;

    @DynamicPropertySource
    static void brokerIdentityAndStore(final DynamicPropertyRegistry registry) throws Exception {
        startTheBrokerAndTap();
        registry.add("cp.audit.hosts", () -> "localhost");
        registry.add("cp.audit.port", () -> URI.create(broker.url()).getPort());
        registry.add("authz.http.identity-url-template", USERSGROUPS::identityUrl);
        PostgresTestSupport.register(registry);
    }

    private static synchronized void startTheBrokerAndTap() throws Exception {
        if (broker == null) {
            broker = EmbeddedBrokerSupport.start("audit-it", 3);
            tapFactory = new ActiveMQConnectionFactory(broker.url());
            tap = tapFactory.createContext();
            tapConsumer = tap.createConsumer(tap.createTopic(AUDIT_TOPIC));
            tapConsumer.setMessageListener(message -> {
                try {
                    PUBLISHED.add(((TextMessage) message).getText());
                } catch (JMSException e) {
                    throw new IllegalStateException("unreadable audit message", e);
                }
            });
        }
    }

    @AfterAll
    static void closeTheTap() {
        tapConsumer.close();
        tap.close();
        tapFactory.close();
    }

    @BeforeEach
    void aShareOfItsOwn() {
        jdbc.sql("TRUNCATE event_receipt, share_defendant, hearing_share_payload, hearing_share, hearing_day_head")
                .update();
        hearingId = UUID.randomUUID();
    }

    private UUID stored(final String note) {
        final JdbcReceiptStore receipts = new JdbcReceiptStore(jdbc, new TransactionTemplate(transactionManager));
        final JdbcShareStore store = new JdbcShareStore(jdbc, new TransactionTemplate(transactionManager), receipts,
                JdbcShareStore.Timeouts.DEFAULTS);
        final String text = SampleShares.share(hearingId, DAY, "2026-10-02T09:00:00Z", "false", note);
        final String messageId = "ID:audit-" + UUID.randomUUID();
        receipts.recordArrival(SampleShares.arrival(messageId, text));
        final StoreRequest request = SampleShares.request(messageId, text);
        store.store(request);
        return request.shareId();
    }

    private Exchange get(final String path, final Map<String, String> headers) throws IOException,
            InterruptedException {
        final String correlation = UUID.randomUUID().toString();
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET()
                .header(CORRELATION, correlation);
        headers.forEach(request::header);
        return new Exchange(correlation, CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static Map<String, String> caller(final String user, final String... more) {
        final Map<String, String> headers = new HashMap<>();
        headers.put(USER_ID_HEADER, user);
        for (int index = 0; index < more.length; index += 2) {
            headers.put(more[index], more[index + 1]);
        }
        return headers;
    }

    /** The events of one exchange, once {@code count} have arrived. */
    private static List<JsonNode> events(final Exchange exchange, final int count) {
        await().atMost(WITHIN).until(() -> eventsOf(exchange).size() >= count);
        return eventsOf(exchange);
    }

    /** The events of one exchange after a quiet spell, for an exchange expected to publish fewer. */
    private static List<JsonNode> settledEvents(final Exchange exchange) {
        await().pollDelay(QUIET).atMost(QUIET.plusSeconds(1)).until(() -> true);
        return eventsOf(exchange);
    }

    private static List<JsonNode> eventsOf(final Exchange exchange) {
        return PUBLISHED.stream().filter(event -> event.contains(exchange.correlation())).map(MAPPER::readTree).toList();
    }

    private static JsonNode content(final JsonNode event) {
        return event.get("content");
    }

    /** The response event of an exchange: the one whose content is not the request's empty {@code _payload}. */
    private static List<JsonNode> responseEvents(final List<JsonNode> events) {
        return events.stream().filter(event -> !content(event).has("_payload")).toList();
    }

    @Test
    void the_request_event_should_carry_the_caller_and_no_caller_chosen_name() throws Exception {
        final UUID shareId = stored("");

        final Exchange exchange = get("/results-store/v1/shares/" + shareId, caller(SYSTEM_USER,
                "CPP-ACTION", "results-store.get-share-payload",
                "Accept", "application/vnd.results-store.get-share-payload+json"));

        final List<JsonNode> events = events(exchange, 2);
        final JsonNode request = events.stream().filter(event -> content(event).has("_payload")).findFirst()
                .orElseThrow();
        assertThat(exchange.response().statusCode()).isEqualTo(200);
        assertThat(request.get("_metadata").get("context").get("user").asString()).isEqualTo(SYSTEM_USER);
        assertThat(request.get("_metadata").get("name").asString()).isEqualTo("audit.events.audit-recorded");
        // The library names the inner record from Accept or Content-Type, as the action filter left them: a
        // vendor type is read as JSON, so a caller cannot name the record either.
        assertThat(content(request).get("_metadata").get("name").asString()).isEqualTo("application/json");
        assertThat(request.toString()).doesNotContain("results-store.get-share-payload");
    }

    @Test
    void the_day_route_request_event_should_carry_its_path_parameters() throws Exception {
        stored("");

        final Exchange exchange = get("/results-store/v1/hearings/" + hearingId + "/days/" + DAY + "/shares",
                caller(SYSTEM_USER));

        final JsonNode request = events(exchange, 2).stream().filter(event -> content(event).has("_payload"))
                .findFirst().orElseThrow();
        assertThat(content(request).get("hearingId").asString()).isEqualTo(hearingId.toString());
        assertThat(content(request).get("hearingDay").asString()).isEqualTo(DAY);
    }

    /**
     * Pins a gap: the library resolves path parameters only where the document declares them inline, and the
     * share routes take {@code shareId} by {@code $ref} (as the contract jar does), so their request event has no
     * {@code shareId}. Changing it is a contract change (Deferred).
     */
    @Test
    void the_share_routes_request_event_should_not_carry_a_share_id_declared_by_reference() throws Exception {
        final UUID shareId = stored("");

        final Exchange exchange = get("/results-store/v1/shares/" + shareId, caller(SYSTEM_USER));

        final JsonNode request = events(exchange, 2).stream().filter(event -> content(event).has("_payload"))
                .findFirst().orElseThrow();
        assertThat(content(request).has("shareId")).isFalse();
    }

    @Test
    void the_payload_response_event_should_carry_the_marker_and_no_payload_byte() throws Exception {
        final UUID shareId = stored(MARKER);

        final Exchange exchange = get("/results-store/v1/shares/" + shareId + "/payload", caller(SYSTEM_USER));

        assertThat(exchange.response().statusCode()).isEqualTo(200);
        assertThat(new String(exchange.response().body(), StandardCharsets.UTF_8)).contains(MARKER);
        final List<JsonNode> events = events(exchange, 2);
        assertThat(responseEvents(events)).singleElement()
                .satisfies(event -> assertThat(content(event).path("payloadOmitted").asBoolean()).isTrue());
        // Named by count only, so a failure prints no payload content.
        assertThat(events.stream().filter(event -> event.toString().contains(MARKER)).count())
                .as("events carrying payload content").isZero();
        assertThat(events.stream().filter(event -> event.toString().contains("prosecutionCases")).count())
                .as("events carrying payload content").isZero();
    }

    /** Phase D: the arrived text's response event carries the marker too (the override keys on the derived action). */
    @Test
    void arrived_response_event_should_carry_the_marker() throws Exception {
        final UUID shareId = stored(MARKER);

        final Exchange exchange = get("/results-store/v1/shares/" + shareId + "/payload/arrived", caller(SYSTEM_USER,
                "CPP-ACTION", "results-store.get-share"));

        assertThat(exchange.response().statusCode()).isEqualTo(200);
        assertThat(new String(exchange.response().body(), StandardCharsets.UTF_8)).contains(MARKER);
        final List<JsonNode> events = events(exchange, 2);
        assertThat(responseEvents(events)).singleElement()
                .satisfies(event -> assertThat(content(event).path("payloadOmitted").asBoolean()).isTrue());
        // Named by count only, so a failure prints no payload content.
        assertThat(events.stream().filter(event -> event.toString().contains(MARKER)).count())
                .as("events carrying payload content").isZero();
        assertThat(events.stream().filter(event -> event.toString().contains("prosecutionCases")).count())
                .as("events carrying payload content").isZero();
    }

    @Test
    void a_list_response_event_should_carry_the_page() throws Exception {
        final UUID shareId = stored("");

        final Exchange exchange = get("/results-store/v1/hearings/" + hearingId + "/days/" + DAY + "/shares",
                caller(SYSTEM_USER));

        assertThat(responseEvents(events(exchange, 2))).singleElement().satisfies(event -> assertThat(
                content(event).get("items").get(0).get("shareId").asString()).isEqualTo(shareId.toString()));
    }

    @Test
    void a_304_should_publish_no_response_event() throws Exception {
        final String path = "/results-store/v1/shares/" + stored("") + "/payload";
        final String etag = get(path, caller(SYSTEM_USER)).response().headers().firstValue("ETag").orElseThrow();

        final Exchange exchange = get(path, caller(SYSTEM_USER, "If-None-Match", etag));

        assertThat(exchange.response().statusCode()).isEqualTo(304);
        events(exchange, 1);
        assertThat(settledEvents(exchange)).hasSize(1).allSatisfy(event -> assertThat(content(event).has("_payload"))
                .isTrue());
    }

    @Test
    void a_multipart_request_should_be_refused_415_and_publish_nothing() throws Exception {
        final Exchange exchange = get("/results-store/v1/shares/" + stored(""),
                caller(SYSTEM_USER, "Content-Type", "multipart/form-data; boundary=x"));

        assertThat(exchange.response().statusCode()).isEqualTo(415);
        assertThat(settledEvents(exchange)).isEmpty();
    }

    @Test
    void a_401_and_a_403_should_publish_nothing() throws Exception {
        final String path = "/results-store/v1/shares/" + stored("");

        final Exchange unauthenticated = get(path, Map.of());
        final Exchange forbidden = get(path, caller(NO_GROUP_USER));

        assertThat(unauthenticated.response().statusCode()).isEqualTo(401);
        assertThat(forbidden.response().statusCode()).isEqualTo(403);
        assertThat(settledEvents(unauthenticated)).isEmpty();
        assertThat(settledEvents(forbidden)).isEmpty();
    }

    /**
     * The override relies on the request's {@code CPP-ACTION} reaching it in {@code ResponseInfo.headers()}, because
     * {@code ResponseInfo.contextPath()} is the servlet context path, empty here, written into each event's
     * {@code origin} (and {@code component} as {@code <contextPath>-api}). A library upgrade that changes either
     * fails this test.
     */
    @Test
    void the_response_info_context_path_should_be_the_form_the_override_matches() throws Exception {
        assertThat(generation).isInstanceOf(PayloadBodyFreeAuditPayloadGenerationService.class);

        final Exchange exchange = get("/results-store/v1/shares/" + stored("") + "/payload", caller(SYSTEM_USER));

        assertThat(events(exchange, 2)).allSatisfy(event -> {
            assertThat(event.get("origin").asString()).isEmpty();
            assertThat(event.get("component").asString()).isEqualTo("-api");
        });
        assertThat(responseEvents(events(exchange, 2))).singleElement()
                .satisfies(event -> assertThat(content(event).has("payloadOmitted")).isTrue());
    }

    @Test
    void with_the_audit_wrapper_the_payload_should_keep_content_length_and_no_chunked_encoding() throws Exception {
        final Exchange exchange = get("/results-store/v1/shares/" + stored(MARKER) + "/payload", caller(SYSTEM_USER));

        assertThat(exchange.response().headers().firstValue("Content-Length"))
                .contains(Integer.toString(exchange.response().body().length));
        assertThat(exchange.response().headers().firstValue("Transfer-Encoding")).isEmpty();
        assertThat(exchange.response().headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(exchange.response().headers().firstValue("ETag")).hasValueSatisfying(etag -> assertThat(etag)
                .isEqualTo("\"" + PayloadChecksum.sha256Hex(
                        exchange.response().body()) + "\""));
    }

    /** One request and its answer, with the correlation id that marks its events. */
    private record Exchange(String correlation, HttpResponse<byte[]> response) {
    }
}
