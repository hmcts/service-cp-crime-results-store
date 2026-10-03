package uk.gov.hmcts.cp.resultsstore.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

/**
 * Requests the HTTP connector rejects before they reach the service (contracts/read-api.md §6), on a real
 * server and over a raw socket, since an HTTP client would refuse to send most of them. Tomcat answers each
 * {@code 400} itself; the host's error report writes the four-field problem body, never the URI, and the
 * refusal is not counted. A path with a dot segment, which the connector accepts and normalises, is refused by
 * the action filter instead, on its raw text.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("connector-level rejections")
class ConnectorRejectionIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String HEADERS_END = "\r\n\r\n";

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void store(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    private record RawResponse(String head, String body) {

        int status() {
            return Integer.parseInt(head.substring(head.indexOf(' ') + 1, head.indexOf(' ') + 4));
        }

        String header(final String name) {
            return head.lines()
                    .filter(line -> line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                    .map(line -> line.substring(name.length() + 1).trim())
                    .findFirst()
                    .orElse("");
        }
    }

    private RawResponse send(final String target) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close"
                    + HEADERS_END).getBytes(StandardCharsets.US_ASCII));
            final ByteArrayOutputStream all = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(all);
            final String text = all.toString(StandardCharsets.UTF_8);
            final int split = text.indexOf(HEADERS_END);
            return new RawResponse(text.substring(0, split), text.substring(split + HEADERS_END.length()));
        }
    }

    private double refused(final String reason) {
        final Counter counter = meterRegistry.find("resultsstore.read.refused").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private double refusedInAll() {
        return meterRegistry.find("resultsstore.read.refused").counters().stream().mapToDouble(Counter::count).sum();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/results-store/v1/shares/zq%2Fsecret", "/results-store/v1/shares/zq%00secret",
        "/results-store/v1/shares/zq%zzsecret", "/results-store/v1/shares/zq%5Csecret",
        "/results-store/v1/shares/zq%secret", "/results-store/v1/shares/zq|secret", "/results-store/v1/shares/zq{secret",
        "/results-store/v1/shares?zq=|secret"})
    void a_uri_the_connector_rejects_should_get_a_400_with_the_four_field_problem_body(final String target)
            throws IOException {
        final double refused = refusedInAll();

        final RawResponse response = send(target);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.header("Content-Type")).isEqualTo("application/problem+json");
        assertThat(response.body()).doesNotContain("zq").doesNotContain("results-store");
        final JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.propertyNames()).containsExactly("type", "title", "status", "reason");
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.get("title").asString()).isEqualTo("Bad Request");
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("reason").asString()).isEqualTo("bad_request");
        assertThat(refusedInAll()).isEqualTo(refused);
    }

    /**
     * Tomcat accepts a dot segment and maps the normalised path, but the action filter classifies the raw URI:
     * a path that only looks like {@code /actuator/**} or {@code /error} must not pass through to a route.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/../results-store/v1/shares?storedAfterSeq=0",
        "/actuator/%2e%2e/results-store/v1/shares?storedAfterSeq=0",
        "/actuator/..;zq=1/results-store/v1/shares?storedAfterSeq=0",
        "/error/../results-store/v1/shares?storedAfterSeq=0"})
    void a_dot_segment_should_be_refused_404_route_not_found_and_counted(final String target) throws IOException {
        final double notFound = refused("route_not_found");

        final RawResponse response = send(target);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("Content-Type")).isEqualTo("application/problem+json");
        assertThat(response.body()).doesNotContain("zq").doesNotContain("results-store");
        final JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.propertyNames()).containsExactly("type", "title", "status", "reason");
        assertThat(body.get("reason").asString()).isEqualTo("route_not_found");
        assertThat(refused("route_not_found")).isEqualTo(notFound + 1);
    }
}
