package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.ApplicationAnswer;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;
import uk.gov.hmcts.cp.resultsstore.support.DribblingServer;
import uk.gov.hmcts.cp.resultsstore.support.ProgressionStub;

/**
 * One test per row of specs/002-enrichment/contracts/progression-lookup.md, against an in-process
 * WireMock. Every test uses its own application id, and stubs and requests are matched by that id.
 */
@DisplayName("progression application client")
class ProgressionApplicationClientTest {

    private static final ProgressionStub PROGRESSION = ProgressionStub.start();

    private static final String SYSTEM_USER_ID = "0b0e7f5c-1d2e-4f3a-9b8c-7d6e5f4a3b2c";

    /** Short, so the timeout rows end quickly; the production default is 10 s. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

    /** Planted in every failing body: it must never reach a log line or an exception message. */
    private static final String MARKER = "BODY-MARKER-7f3c";

    /** Headers the transport adds by itself; anything else was set by the store. */
    private static final Set<String> TRANSPORT_HEADERS = Set.of("host", "user-agent", "connection");

    private static final String FINALISED = """
            {"courtApplication": {"id": "%s", "applicationStatus": "FINALISED",
             "judicialResults": [{"judicialResultId": "11111111-2222-4333-8444-555555555555", "label": "Fine"}]}}""";

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private final ProgressionApplicationClient client = clientAt(PROGRESSION.baseUrl());

    @AfterAll
    static void stopProgression() {
        PROGRESSION.close();
    }

    private ProgressionApplicationClient clientAt(final String baseUrl) {
        return clientAt(baseUrl, READ_TIMEOUT);
    }

    /** The production wiring gives the deadline the read timeout; a longer one isolates the socket timeout. */
    private ProgressionApplicationClient clientAt(final String baseUrl, final Duration responseDeadline) {
        final RestClient rest = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new NoRedirectRequestFactory(CONNECT_TIMEOUT, READ_TIMEOUT, responseDeadline))
                .build();
        return new ProgressionApplicationClient(rest, SYSTEM_USER_ID, responseDeadline, mapper);
    }

    private static UUID answered(final ResponseDefinitionBuilder response) {
        final UUID applicationId = UUID.randomUUID();
        PROGRESSION.answer(applicationId, response);
        return applicationId;
    }

    private static RetryableIntakeException failed(final Throwable thrown, final IntakeFailureCause cause) {
        assertThat(thrown).isInstanceOf(RetryableIntakeException.class);
        final RetryableIntakeException failure = (RetryableIntakeException) thrown;
        assertThat(failure.getStage()).isEqualTo(IntakeStage.ENRICH);
        assertThat(failure.getFailureCause()).isEqualTo(cause);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getFailedClassName()).isPresent();
        assertThat(failure.getMessage()).doesNotContain(MARKER).doesNotContain(SYSTEM_USER_ID)
                .startsWith("intake failed at ENRICH: " + cause.name() + " (")
                .endsWith(failure.getFailedClassName().orElseThrow() + ")");
        return failure;
    }

    private void assertFailsWith(final UUID applicationId, final IntakeFailureCause cause) {
        assertThatThrownBy(() -> client.find(applicationId)).satisfies(thrown -> failed(thrown, cause));
    }

    @Nested
    @DisplayName("request")
    class Request {

        @Test
        void lookup_should_get_the_application_by_path_with_accept_and_the_system_user_only() {
            final UUID applicationId = answered(okJson(FINALISED.formatted(UUID.randomUUID())));

            client.find(applicationId);

            final List<LoggedRequest> requests = PROGRESSION.requestsFor(applicationId);
            assertThat(requests).hasSize(1);
            final LoggedRequest request = requests.getFirst();
            assertThat(request.getMethod().getName()).isEqualTo("GET");
            assertThat(request.getUrl()).isEqualTo(ProgressionStub.pathFor(applicationId));
            assertThat(request.getHeader("Accept")).isEqualTo(ProgressionStub.MEDIA_TYPE);
            assertThat(request.getHeader("CJSCPPUID")).isEqualTo(SYSTEM_USER_ID);
            assertThat(request.getBody()).isEmpty();
            final Set<String> storeHeaders = request.getAllHeaderKeys().stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .filter(name -> !TRANSPORT_HEADERS.contains(name))
                    .collect(Collectors.toSet());
            assertThat(storeHeaders).containsExactlyInAnyOrder("accept", "cjscppuid");
        }

        @Test
        void failed_lookup_should_be_sent_once_with_no_retry() {
            final UUID applicationId = answered(aResponse().withStatus(503));

            assertFailsWith(applicationId, IntakeFailureCause.PROGRESSION_UNAVAILABLE);

            assertThat(PROGRESSION.requestsFor(applicationId)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("answered 200")
    class Answered {

        @Test
        void finalised_application_with_results_should_be_found() {
            final UUID applicationId = answered(okJson(FINALISED.formatted("aaaaaaaa-1111-4222-8333-444444444444")));

            final ApplicationAnswer answer = client.find(applicationId);

            assertThat(answer).isInstanceOfSatisfying(ApplicationAnswer.Found.class, found -> {
                assertThat(found.courtApplication().get("id").asString())
                        .isEqualTo("aaaaaaaa-1111-4222-8333-444444444444");
                assertThat(found.courtApplication().get("judicialResults").isArray()).isTrue();
            });
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "{}",
            "{\"courtApplication\": null}",
            "{\"other\": 1}"
        })
        void empty_answer_or_no_application_should_be_not_found(final String body) {
            assertThat(client.find(answered(okJson(body)))).isInstanceOf(ApplicationAnswer.NotFound.class);
        }

        /** Whether the application is finalised and has results is the enricher's reading (T004). */
        @ParameterizedTest
        @ValueSource(strings = {
            "{\"courtApplication\": {\"applicationStatus\": \"LISTED\"}}",
            "{\"courtApplication\": {\"applicationStatus\": \"FINALISED\"}}",
            "{\"courtApplication\": {\"applicationStatus\": \"FINALISED\", \"judicialResults\": null}}",
            "{\"courtApplication\": {\"applicationStatus\": \"FINALISED\", \"judicialResults\": []}}",
            "{\"courtApplication\": {\"applicationStatus\": 7, \"judicialResults\": [{}]}}",
            "{\"courtApplication\": {}}"
        })
        void application_in_any_status_or_with_no_results_should_be_found(final String body) {
            assertThat(client.find(answered(okJson(body)))).isInstanceOf(ApplicationAnswer.Found.class);
        }

        @Test
        void decimals_should_keep_their_value_and_written_precision() {
            final UUID applicationId = answered(okJson("""
                    {"courtApplication": {"applicationStatus": "FINALISED", "judicialResults": [
                      {"amount": 1.10, "large": 12345678901234567890.123}]}}"""));

            final ApplicationAnswer answer = client.find(applicationId);

            assertThat(answer).isInstanceOfSatisfying(ApplicationAnswer.Found.class, found -> {
                final JsonNode result = found.courtApplication().get("judicialResults").get(0);
                assertThat(result.get("amount").decimalValue()).isEqualTo(new BigDecimal("1.10"));
                assertThat(result.get("large").decimalValue())
                        .isEqualTo(new BigDecimal("12345678901234567890.123"));
                assertThat(mapper.writeValueAsString(result))
                        .isEqualTo("{\"amount\":1.10,\"large\":12345678901234567890.123}");
            });
        }
    }

    @Nested
    @DisplayName("answered with another status")
    class Statuses {

        @ParameterizedTest
        @CsvSource({
            "404, PROGRESSION_REJECTED",
            "201, PROGRESSION_REJECTED",
            "204, PROGRESSION_REJECTED",
            "400, PROGRESSION_REJECTED",
            "405, PROGRESSION_REJECTED",
            "406, PROGRESSION_REJECTED",
            "410, PROGRESSION_REJECTED",
            "415, PROGRESSION_REJECTED",
            "418, PROGRESSION_REJECTED",
            "401, PROGRESSION_REFUSED",
            "403, PROGRESSION_REFUSED",
            "408, PROGRESSION_UNAVAILABLE",
            "429, PROGRESSION_UNAVAILABLE",
            "500, PROGRESSION_UNAVAILABLE",
            "502, PROGRESSION_UNAVAILABLE",
            "503, PROGRESSION_UNAVAILABLE",
            "504, PROGRESSION_UNAVAILABLE"
        })
        void status_should_fail_closed_with_its_cause(final int status, final IntakeFailureCause cause) {
            final UUID applicationId = answered(aResponse().withStatus(status)
                    .withHeader("Content-Type", "application/json").withBody("{\"marker\": \"" + MARKER + "\"}"));

            assertThatThrownBy(() -> client.find(applicationId)).satisfies(thrown ->
                    assertThat(failed(thrown, cause).getFailedClassName()).contains("status " + status));
        }

        /**
         * The body of a non-200 is never read or drained: the request is aborted on the status, so a
         * body dribbled for longer than the read timeout neither delays the answer nor turns it into a
         * timeout.
         */
        @Test
        void unavailable_status_should_not_wait_for_a_dribbled_body() {
            final UUID applicationId = answered(aResponse().withStatus(503)
                    .withBody("{\"marker\": \"" + MARKER + "\", \"padding\": \"" + "x".repeat(64) + "\"}")
                    .withChunkedDribbleDelay(30, 3_000));

            try (CapturedLog log = CapturedLog.forClass(ProgressionApplicationClient.class)) {
                final long start = System.nanoTime();

                assertThatThrownBy(() -> client.find(applicationId))
                        .satisfies(thrown -> failed(thrown, IntakeFailureCause.PROGRESSION_UNAVAILABLE));

                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(READ_TIMEOUT.dividedBy(2));
                assertThat(log.messages()).singleElement().satisfies(line -> assertThat(line)
                        .contains("progression_unavailable").doesNotContain("progression_timeout"));
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {301, 302, 303, 307, 308})
        void redirect_should_fail_closed_and_should_not_be_followed(final int status) {
            final UUID applicationId = UUID.randomUUID();
            final String target = "/redirected/" + applicationId;
            PROGRESSION.server().stubFor(get(urlPathEqualTo(target)).willReturn(okJson("{}")));
            PROGRESSION.answer(applicationId,
                    aResponse().withStatus(status).withHeader("Location", PROGRESSION.baseUrl() + target));

            assertFailsWith(applicationId, IntakeFailureCause.PROGRESSION_REJECTED);

            assertThat(PROGRESSION.server().findAll(getRequestedFor(urlPathEqualTo(target)))).isEmpty();
        }
    }

    @Nested
    @DisplayName("not answered")
    class NotAnswered {

        @Test
        void closed_port_should_be_unreachable() throws IOException {
            final int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            final ProgressionApplicationClient closed = clientAt("http://127.0.0.1:" + port);

            assertThatThrownBy(() -> closed.find(UUID.randomUUID())).satisfies(thrown -> assertThat(
                    failed(thrown, IntakeFailureCause.PROGRESSION_UNREACHABLE).getFailedClassName())
                    .hasValueSatisfying(name -> assertThat(name).endsWith("ConnectException")));
        }

        @Test
        void unknown_host_should_be_unreachable() {
            final ProgressionApplicationClient unknown = clientAt("http://progression.invalid");

            assertThatThrownBy(() -> unknown.find(UUID.randomUUID())).satisfies(thrown -> assertThat(
                    failed(thrown, IntakeFailureCause.PROGRESSION_UNREACHABLE).getFailedClassName())
                    .contains("UnknownHostException"));
        }

        @Test
        void connection_reset_before_the_status_line_should_be_unreachable_and_sent_once() {
            final UUID applicationId = answered(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

            assertFailsWith(applicationId, IntakeFailureCause.PROGRESSION_UNREACHABLE);

            // No transport retry either: HttpURLConnection silently resends a GET after such a reset.
            assertThat(PROGRESSION.requestsFor(applicationId)).hasSize(1);
        }

        @Test
        void answer_slower_than_the_read_timeout_should_time_out_at_the_socket() {
            final UUID applicationId = answered(okJson("{}").withFixedDelay(2_000));
            final ProgressionApplicationClient patient = clientAt(PROGRESSION.baseUrl(), Duration.ofSeconds(5));

            assertThatThrownBy(() -> patient.find(applicationId)).satisfies(thrown -> assertThat(
                    failed(thrown, IntakeFailureCause.PROGRESSION_TIMEOUT).getFailedClassName())
                    .contains("SocketTimeoutException"));
        }

        @Test
        void body_dribbled_past_the_deadline_should_time_out_though_each_read_is_quick() {
            // Eight chunks 300 ms apart: every socket read returns well inside the 1 s read timeout,
            // but the whole body takes 2.4 s.
            final UUID applicationId = answered(okJson("{\"courtApplication\": {\"applicationStatus\": \"LISTED\","
                    + " \"padding\": \"" + "x".repeat(64) + "\"}}").withChunkedDribbleDelay(8, 2_400));

            // The deadline guard on the body or the factory's cancellation, whichever comes first.
            assertThatThrownBy(() -> client.find(applicationId))
                    .satisfies(thrown -> failed(thrown, IntakeFailureCause.PROGRESSION_TIMEOUT));
        }

        /** Status line and headers one byte every 60 ms: each read is quick, the whole head takes 3 s or more. */
        @ParameterizedTest
        @ValueSource(strings = {
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}",
            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n"
        })
        void status_and_headers_dribbled_past_the_deadline_should_time_out(final String response)
                throws IOException {
            try (DribblingServer slow = DribblingServer.start(response, Duration.ofMillis(60))) {
                final ProgressionApplicationClient dribbled = clientAt(slow.baseUrl());
                final long start = System.nanoTime();

                assertThatThrownBy(() -> dribbled.find(UUID.randomUUID()))
                        .satisfies(thrown -> failed(thrown, IntakeFailureCause.PROGRESSION_TIMEOUT));

                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2_500));
                assertThat(slow.requests()).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("answered 200 with a broken body")
    class Malformed {

        @Test
        void malformed_chunk_should_be_malformed() {
            assertFailsWith(answered(aResponse().withStatus(200).withFault(Fault.MALFORMED_RESPONSE_CHUNK)),
                    IntakeFailureCause.PROGRESSION_MALFORMED);
        }

        @Test
        void html_should_be_malformed() {
            assertFailsWith(answered(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                    .withBody("<html><body>" + MARKER + "</body></html>")), IntakeFailureCause.PROGRESSION_MALFORMED);
        }

        @Test
        void empty_body_should_be_malformed() {
            final UUID applicationId = answered(aResponse().withStatus(200));

            assertThatThrownBy(() -> client.find(applicationId)).satisfies(thrown -> assertThat(
                    failed(thrown, IntakeFailureCause.PROGRESSION_MALFORMED).getFailedClassName())
                    .contains("MissingNode"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "[{\"courtApplication\": {\"id\": \"" + MARKER + "\"}}]",
            "\"" + MARKER + "\"",
            "42",
            "null",
            "{\"courtApplication\": null} " + MARKER,
            "{\"courtApplication\": {\"id\": \"" + MARKER + "\"",
            "{\"courtApplication\": \"" + MARKER + "\"}",
            "{\"courtApplication\": [\"" + MARKER + "\"]}",
            "{\"courtApplication\": {\"applicationStatus\": \"FINALISED\", \"judicialResults\": {\"m\": \""
                    + MARKER + "\"}}}",
            "{\"courtApplication\": {\"applicationStatus\": \"FINALISED\", \"judicialResults\": \"" + MARKER + "\"}}"
        })
        void body_of_the_wrong_shape_should_be_malformed(final String body) {
            assertFailsWith(answered(okJson(body)), IntakeFailureCause.PROGRESSION_MALFORMED);
        }
    }

    @Nested
    @DisplayName("logging")
    class Logging {

        @Test
        void failures_should_be_logged_with_no_body_and_no_system_user() {
            try (CapturedLog log = CapturedLog.forClass(ProgressionApplicationClient.class)) {
                final List<UUID> ids = List.of(
                        answered(okJson("{\"courtApplication\": \"" + MARKER + "\"}")),
                        answered(okJson("{\"courtApplication\": {\"id\": \"" + MARKER + "\"")),
                        answered(aResponse().withStatus(500).withHeader("Content-Type", "text/html")
                                .withBody("<html>" + MARKER + "</html>")),
                        answered(aResponse().withStatus(403).withBody("{\"error\": \"" + MARKER + "\"}")));
                ids.forEach(id -> assertThatThrownBy(() -> client.find(id))
                        .isInstanceOf(RetryableIntakeException.class));

                assertThat(log.messages()).hasSize(ids.size())
                        .allSatisfy(line -> assertThat(line).doesNotContain(MARKER).doesNotContain(SYSTEM_USER_ID))
                        .anySatisfy(line -> assertThat(line).contains(ids.getFirst().toString()));
                assertThat(log.events()).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            }
        }
    }
}
