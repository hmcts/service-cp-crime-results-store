package uk.gov.hmcts.cp.resultsstore.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

/**
 * A request target the HTTP connector rejects never reaches a log line (constitution XI): Tomcat logs the
 * first parse failure of each request processor at {@code INFO}, with the exception, whose message quotes the
 * target. The capture is on the real logback context at its configured levels. The processor cache is off, so
 * every connection gets a new processor and each rejection here is that processor's first, whatever ran
 * before in the JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.processor-cache=0")
@ActiveProfiles("test")
@DisplayName("connector-level rejections in the logs")
class ConnectorRejectionLogIT {

    private static final String MARKER = "marker-9f3c";

    private static final String HEADERS_END = "\r\n\r\n";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void store(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    private String send(final String target) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close"
                    + HEADERS_END).getBytes(StandardCharsets.US_ASCII));
            final ByteArrayOutputStream all = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(all);
            return all.toString(StandardCharsets.UTF_8);
        }
    }

    private static List<String> textOf(final ILoggingEvent event) {
        final List<String> text = new ArrayList<>();
        text.add(event.getLoggerName());
        text.add(event.getFormattedMessage());
        IThrowableProxy thrown = event.getThrowableProxy();
        while (thrown != null) {
            text.add(thrown.getClassName());
            text.add(String.valueOf(thrown.getMessage()));
            thrown = thrown.getCause();
        }
        return text;
    }

    @Test
    void a_rejected_target_should_reach_no_log_line() throws IOException {
        final List<String> statusLines = new ArrayList<>();
        try (CapturedLog log = CapturedLog.everyLogger()) {
            for (final String target : List.of("/results-store/v1/shares/zq|" + MARKER,
                    "/results-store/v1/shares/zq{" + MARKER, "/results-store/v1/shares?zq=|" + MARKER,
                    "/results-store/v1/shares/zq%2F" + MARKER, "/results-store/v1/shares/zq%00" + MARKER,
                    "/results-store/v1/shares/zq%zz" + MARKER, "/results-store/v1/shares/zq%5C" + MARKER,
                    "/results-store/v1/shares/zq%" + MARKER)) {
                statusLines.add(send(target).lines().findFirst().orElse(""));
            }

            assertThat(statusLines).allSatisfy(line -> assertThat(line).startsWith("HTTP/1.1 400"));
            assertThat(log.events()).flatExtracting(ConnectorRejectionLogIT::textOf)
                    .noneSatisfy(text -> assertThat(text).contains(MARKER))
                    .noneSatisfy(text -> assertThat(text).contains("IllegalArgumentException"));
        }
    }
}
