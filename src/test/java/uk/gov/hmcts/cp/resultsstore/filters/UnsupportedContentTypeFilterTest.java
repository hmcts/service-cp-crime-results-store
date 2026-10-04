package uk.gov.hmcts.cp.resultsstore.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.samplePath;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;
import uk.gov.hmcts.cp.resultsstore.support.BrokenPipeResponse;
import uk.gov.hmcts.cp.resultsstore.support.RecordingRefusalObserver;

/** The {@code 415} guard (FR-048 (d)): no request to the read API has a body, so multipart is refused. */
@DisplayName("the unsupported content type filter")
class UnsupportedContentTypeFilterTest {

    private final RecordingRefusalObserver observer = new RecordingRefusalObserver();

    private final UnsupportedContentTypeFilter guard = new UnsupportedContentTypeFilter(observer);

    private static MockHttpServletRequest onRoute(final String contentType) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", samplePath(ApiRoute.GET_SHARE));
        request.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, ApiRoute.GET_SHARE);
        if (contentType != null) {
            request.setContentType(contentType);
            request.setAttribute(ActionHeaderFilter.SENT_CONTENT_TYPE_ATTRIBUTE, contentType);
        }
        return request;
    }

    private MockFilterChain filter(final MockHttpServletRequest request, final MockHttpServletResponse response)
            throws ServletException, IOException {
        final MockFilterChain chain = new MockFilterChain();
        guard.doFilter(request, response, chain);
        return chain;
    }

    @ParameterizedTest
    @ValueSource(strings = {"multipart/form-data; boundary=x", "multipart/mixed", "MULTIPART/Form-Data"})
    void multipart_on_a_mapped_route_should_be_refused_415_unsupported_content_type_and_counted(
            final String contentType) throws ServletException, IOException {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        final MockFilterChain chain = filter(onRoute(contentType), response);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(415);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        final JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "reason");
        assertThat(body.get("reason").asString()).isEqualTo("unsupported_content_type");
        assertThat(body.get("title").asString()).isEqualTo("Unsupported Media Type");
        assertThat(observer.refusals()).containsExactly(RouteRefusal.UNSUPPORTED_CONTENT_TYPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json;charset=UTF-8", "text/plain", ""})
    void json_and_absent_content_type_should_pass(final String contentType) throws ServletException, IOException {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        final MockFilterChain chain = filter(onRoute(contentType.isEmpty() ? null : contentType), response);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(observer.refusals()).isEmpty();
    }

    /**
     * A vendor token in a multipart parameter is neutralised to {@code application/json} for the authorisation
     * library; the guard still classifies the {@code Content-Type} the caller sent.
     */
    @ParameterizedTest
    @ValueSource(strings = {"multipart/related; type=\"application/vnd.results-store.anything+json\"; boundary=x",
        "multipart/form-data; boundary=x; x=application/vnd.results-store.get-share+json"})
    void multipart_with_a_vendor_parameter_behind_the_action_filter_should_still_be_refused_415(
            final String contentType) throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", samplePath(ApiRoute.GET_SHARE));
        request.setContentType(contentType);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final MockServlet servlet = new MockServlet();

        new MockFilterChain(servlet, new ActionHeaderFilter(observer), guard).doFilter(request, response);

        assertThat(servlet.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(415);
        assertThat(observer.refusals()).containsExactly(RouteRefusal.UNSUPPORTED_CONTENT_TYPE);
    }

    @Test
    void json_behind_the_action_filter_should_reach_the_servlet() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", samplePath(ApiRoute.GET_SHARE));
        request.setContentType("application/vnd.results-store.anything+json");
        final MockServlet servlet = new MockServlet();

        new MockFilterChain(servlet, new ActionHeaderFilter(observer), guard)
                .doFilter(request, new MockHttpServletResponse());

        assertThat(servlet.called).isTrue();
        assertThat(observer.refusals()).isEmpty();
    }

    /** Records whether the request got past the filters. */
    private static final class MockServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private boolean called;

        @Override
        protected void service(final HttpServletRequest request, final HttpServletResponse response) {
            called = true;
        }
    }

    @Test
    void a_415_whose_body_cannot_be_written_should_not_be_counted() {
        final MockHttpServletRequest request = onRoute("multipart/form-data; boundary=x");

        assertThatThrownBy(() -> guard.doFilter(request, new BrokenPipeResponse(), new MockFilterChain()))
                .isInstanceOf(IOException.class).hasMessage(BrokenPipeResponse.BROKEN_PIPE);
        assertThat(observer.refusals()).isEmpty();
    }

    @Test
    void actuator_should_never_be_refused() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/health");
        request.setContentType("multipart/form-data; boundary=x");
        final MockHttpServletResponse response = new MockHttpServletResponse();

        final MockFilterChain chain = filter(request, response);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(observer.refusals()).isEmpty();
    }
}
