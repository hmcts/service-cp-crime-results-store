package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;
import uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples;

/** The parameter rules run before Spring binds the arguments, and refuse with the bounded body only. */
@DisplayName("share parameters interceptor")
class ShareParametersInterceptorTest {

    private static final String CANARY = "zz-canary-zz";

    private final ShareParametersInterceptor interceptor = new ShareParametersInterceptor();

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private static MockHttpServletRequest request(final String uri, final String query) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setQueryString(query);
        return request;
    }

    @Test
    void a_refused_parameter_should_be_answered_before_binding_and_the_handler_never_called() throws Exception {
        final MockHttpServletRequest request = request("/results-store/v1/shares", "storedAfterSeq=0&limit=0");
        request.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, ApiRoute.PULL_SHARES);

        final boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(JsonMapper.builder().build().readTree(response.getContentAsString()).get("reason").asString())
                .isEqualTo("limit_out_of_range");
    }

    @Test
    void the_route_should_be_resolved_from_method_and_path_when_no_filter_set_it() throws Exception {
        final boolean proceed = interceptor.preHandle(request("/results-store/v1/shares", "storedAfterSeq=0&cursor=x"),
                response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getContentAsString()).contains("\"conflicting_parameters\"");
    }

    @Test
    void the_path_variables_should_be_checked_from_the_raw_template_values() throws Exception {
        final MockHttpServletRequest request = request("/results-store/v1/shares/1-1-1-1-1", null);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("shareId", "1-1-1-1-1"));

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getContentAsString()).contains("\"invalid_share_id\"");
    }

    @Test
    void a_valid_request_should_pass_to_the_handler() throws Exception {
        final MockHttpServletRequest request = request("/results-store/v1/shares/6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b",
                null);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("shareId", "6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b"));

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    /** Every route that takes a share id refuses a non-canonical one and any query parameter (phase D included). */
    @ParameterizedTest
    @EnumSource(value = ApiRoute.class, names = {"GET_SHARE", "GET_SHARE_PAYLOAD", "GET_SHARE_ARRIVED_PAYLOAD"})
    void a_share_route_should_refuse_a_bad_share_id_and_any_query_parameter(final ApiRoute route) throws Exception {
        final String path = ApiRouteSamples.samplePath(route);
        final MockHttpServletRequest badId = request(path.replace(ApiRouteSamples.SHARE_ID, "1-1-1-1-1"), null);
        badId.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("shareId", "1-1-1-1-1"));
        final MockHttpServletResponse unknown = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(badId, response, new Object())).isFalse();
        assertThat(response.getContentAsString()).contains("\"invalid_share_id\"");
        assertThat(interceptor.preHandle(request(path, "x=1"), unknown, new Object())).isFalse();
        assertThat(unknown.getContentAsString()).contains("\"unknown_parameter\"");
    }

    @Test
    void a_path_no_route_serves_should_pass_to_the_handler() throws Exception {
        assertThat(interceptor.preHandle(request("/results-store/v1/other", "x=1"), response, new Object())).isTrue();
    }

    @Test
    void no_refusal_body_should_echo_a_value() throws Exception {
        final MockHttpServletRequest request = request("/results-store/v1/shares",
                "storedAfterSeq=" + CANARY + "&" + CANARY + "=1");

        interceptor.preHandle(request, response, new Object());

        final JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactly("type", "title", "status", "reason");
        assertThat(response.getContentAsString()).doesNotContain(CANARY).doesNotContain("storedAfterSeq");
    }
}
