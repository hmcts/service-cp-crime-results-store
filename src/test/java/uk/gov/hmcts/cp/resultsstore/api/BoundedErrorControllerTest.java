package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.RequestDispatcher;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;
import uk.gov.hmcts.cp.resultsstore.support.BrokenPipeResponse;
import uk.gov.hmcts.cp.resultsstore.support.RecordingRefusalObserver;

/** The service's own {@code /error} page (FR-043): one JSON answer for every {@code Accept}. */
@DisplayName("the bounded error controller")
class BoundedErrorControllerTest {

    private final RecordingRefusalObserver observer = new RecordingRefusalObserver();

    private final BoundedErrorController controller =
            new BoundedErrorController(new BoundedErrorAttributes(), observer);

    private MockHttpServletResponse error(final int status, final String accept) throws IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        final MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(controller.handleRequest(request, response)).isNull();
        return response;
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/json", "text/html,application/xhtml+xml;q=0.9", "image/png",
        "absent"})
    void accept_text_html_json_and_absent_should_all_get_the_four_field_json_body(final String accept)
            throws IOException {
        final MockHttpServletResponse response = error(401, "absent".equals(accept) ? null : accept);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        final JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactly("type", "title", "status", "reason");
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.get("title").asString()).isEqualTo("Unauthorized");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("reason").asString()).isEqualTo("unauthenticated");
    }

    @Test
    void a_401_should_be_counted_once_as_unauthenticated() throws IOException {
        error(401, null);

        assertThat(observer.refusals()).containsExactly(RouteRefusal.UNAUTHENTICATED);
    }

    @Test
    void a_403_should_be_counted_once_as_forbidden() throws IOException {
        final MockHttpServletResponse response = error(403, "text/html");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(observer.refusals()).containsExactly(RouteRefusal.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void a_refusal_whose_body_cannot_be_written_should_not_be_counted(final int status) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);

        assertThatThrownBy(() -> controller.handleRequest(request, new BrokenPipeResponse()))
                .isInstanceOf(IOException.class).hasMessage(BrokenPipeResponse.BROKEN_PIPE);
        assertThat(observer.refusals()).isEmpty();
    }

    /** contracts/read-api.md §6: {@code application/problem+json}, except {@code 401} and {@code 403}. */
    @ParameterizedTest
    @ValueSource(ints = {400, 404, 405, 415, 500, 503})
    void any_status_but_401_and_403_should_be_written_as_problem_json(final int status) throws IOException {
        final MockHttpServletResponse response = error(status, "text/html");

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        final JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactly("type", "title", "status", "reason");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 405, 500, 503})
    void any_other_status_should_not_be_counted(final int status) throws IOException {
        final MockHttpServletResponse response = error(status, null);

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(observer.refusals()).isEmpty();
    }
}
