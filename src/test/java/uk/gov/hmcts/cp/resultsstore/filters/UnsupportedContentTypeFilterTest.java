package uk.gov.hmcts.cp.resultsstore.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.samplePath;

import jakarta.servlet.ServletException;
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
