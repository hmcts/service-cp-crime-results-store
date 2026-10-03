package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;

/**
 * The host's error report (contracts/read-api.md §6): an error Tomcat answers itself, before the service, gets
 * the four-field problem body and never the request's URI.
 */
@DisplayName("the problem error report valve")
class ProblemErrorReportValveTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ProblemErrorReportValve valve = new ProblemErrorReportValve();

    private final Request request = mock(Request.class);

    private final ByteArrayOutputStream written = new ByteArrayOutputStream();

    private Response errorResponse(final int status) throws IOException {
        final Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(status);
        when(response.getContentWritten()).thenReturn(0L);
        when(response.setErrorReported()).thenReturn(true);
        when(response.getOutputStream()).thenReturn(new Captured(written));
        return response;
    }

    @ParameterizedTest
    @CsvSource({"400, Bad Request, bad_request", "404, Not Found, bad_request", "405, Method Not Allowed, bad_request",
        "414, URI Too Long, bad_request", "499, Bad Request, bad_request", "500, Internal Server Error, internal_error",
        "503, Service Unavailable, internal_error"})
    void an_error_should_get_the_four_field_problem_body(final int status, final String title, final String reason)
            throws IOException {
        final Response response = errorResponse(status);

        valve.report(request, response, null);

        final JsonNode body = MAPPER.readTree(written.toString(StandardCharsets.UTF_8));
        assertThat(body.propertyNames()).containsExactly("type", "title", "status", "reason");
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.get("title").asString()).isEqualTo(title);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("reason").asString()).isEqualTo(reason);
        verify(response).setStatus(status);
        verify(response).setContentType("application/problem+json");
        verify(response).setContentLength(written.size());
        verify(response).finishResponse();
    }

    @Test
    void the_body_should_never_hold_the_uri_or_the_exception() throws IOException {
        when(request.getRequestURI()).thenReturn("/results-store/v1/shares/zq-secret");
        final Response response = errorResponse(400);

        valve.report(request, response, new IllegalStateException("zq-secret-message"));

        assertThat(written.toString(StandardCharsets.UTF_8)).doesNotContain("zq-secret").doesNotContain("results-store");
    }

    @Test
    void a_status_below_400_should_write_nothing() throws IOException {
        final Response response = errorResponse(302);

        valve.report(request, response, null);

        assertThat(written.size()).isZero();
        verify(response, never()).setContentType(anyString());
    }

    @Test
    void a_response_with_content_already_written_should_be_left_alone() throws IOException {
        final Response response = errorResponse(400);
        when(response.getContentWritten()).thenReturn(10L);

        valve.report(request, response, null);

        assertThat(written.size()).isZero();
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void an_error_already_reported_should_be_left_alone() throws IOException {
        final Response response = errorResponse(400);
        when(response.setErrorReported()).thenReturn(false);

        valve.report(request, response, null);

        assertThat(written.size()).isZero();
        verify(response, never()).setStatus(anyInt());
    }

    /** A client that has gone: the failure is logged with the status only, and nothing escapes the valve. */
    @Test
    void a_body_that_cannot_be_written_should_be_logged_with_the_status_only() throws IOException {
        when(request.getRequestURI()).thenReturn("/results-store/v1/shares/zq-secret");
        final Response response = errorResponse(400);
        when(response.getOutputStream()).thenThrow(new IOException("zq-secret broken pipe"));

        try (CapturedLog log = CapturedLog.forClass(ProblemErrorReportValve.class)) {
            valve.report(request, response, null);

            assertThat(log.messages()).hasSize(1);
            assertThat(log.messages().getFirst()).contains("400").doesNotContain("zq-secret");
        }
    }

    /** A servlet output stream over a byte buffer. */
    private static final class Captured extends ServletOutputStream {

        private final ByteArrayOutputStream target;

        Captured(final ByteArrayOutputStream target) {
            super();
            this.target = target;
        }

        @Override
        public void write(final int value) {
            target.write(value);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(final WriteListener listener) {
            // Blocking only.
        }
    }
}
