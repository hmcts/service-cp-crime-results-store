package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/** {@code /error}'s body: the four fields and nothing else (FR-042, FR-043, research R13). */
@DisplayName("bounded error attributes")
class BoundedErrorAttributesTest {

    private static final String SECRET = "zq-secret-value";

    private final BoundedErrorAttributes attributes = new BoundedErrorAttributes();

    private static ServletWebRequest errorRequest(final Integer status) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        if (status != null) {
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        }
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/results-store/v1/" + SECRET);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "message " + SECRET);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("exception " + SECRET));
        return new ServletWebRequest(request);
    }

    @ParameterizedTest
    @CsvSource({"401, Unauthorized, unauthenticated", "403, Forbidden, forbidden"})
    void a_401_and_a_403_should_render_type_title_status_and_reason_only(final int status, final String title,
                                                                          final String reason) {
        final Map<String, Object> body = attributes.getErrorAttributes(errorRequest(status),
                ErrorAttributeOptions.defaults());

        assertThat(body).containsExactly(Map.entry("type", "about:blank"), Map.entry("title", title),
                Map.entry("status", status), Map.entry("reason", reason));
    }

    @ParameterizedTest
    @CsvSource({"400, Bad Request, bad_request", "404, Not Found, bad_request", "405, Method Not Allowed, bad_request",
        "499, Bad Request, bad_request", "500, Internal Server Error, internal_error",
        "503, Service Unavailable, internal_error"})
    void any_other_4xx_should_be_bad_request_and_any_5xx_internal_error(final int status, final String title,
                                                                        final String reason) {
        final Map<String, Object> body = attributes.getErrorAttributes(errorRequest(status),
                ErrorAttributeOptions.defaults());

        assertThat(body).containsExactly(Map.entry("type", "about:blank"), Map.entry("title", title),
                Map.entry("status", status), Map.entry("reason", reason));
    }

    @Test
    void no_status_or_a_non_error_status_should_be_an_internal_error() {
        assertThat(attributes.getErrorAttributes(errorRequest(null), ErrorAttributeOptions.defaults()))
                .containsEntry("status", 500).containsEntry("reason", "internal_error");
        assertThat(attributes.getErrorAttributes(errorRequest(200), ErrorAttributeOptions.defaults()))
                .containsEntry("status", 500).containsEntry("title", "Internal Server Error");
    }

    @Test
    void no_path_message_error_exception_or_trace_should_ever_appear() {
        final Map<String, Object> body = attributes.getErrorAttributes(errorRequest(500), ErrorAttributeOptions.of(
                ErrorAttributeOptions.Include.values()));

        assertThat(body.keySet()).containsExactly("type", "title", "status", "reason");
        assertThat(body.values()).allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(SECRET));
    }
}
