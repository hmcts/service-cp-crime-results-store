package uk.gov.hmcts.cp.resultsstore.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.needsStoredAfterSeq;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.samplePath;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;
import uk.gov.hmcts.cp.resultsstore.support.BrokenPipeResponse;
import uk.gov.hmcts.cp.resultsstore.support.RecordingRefusalObserver;
import uk.gov.moj.cpp.authz.http.RequestActionResolver;

/**
 * The action filter (research R2): the action always comes from method and path, vendor media types are
 * neutralised on mapped routes, unmapped paths and other methods are refused before authorisation.
 */
@DisplayName("the action filter")
class ActionHeaderFilterTest {

    private static final String ACTION_HEADER = "CPP-ACTION";

    private static final String VENDOR = "application/vnd.results-store.get-share-payload+json";

    private static final String JSON = "application/json";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RecordingRefusalObserver observer = new RecordingRefusalObserver();

    private final ActionHeaderFilter actionFilter = new ActionHeaderFilter(observer);

    /** What the caller sends to try to pick its own action. */
    enum Attempt {
        CALLER_ACTION, VENDOR_CONTENT_TYPE, VENDOR_ACCEPT, ACCEPT_LIST_WITH_ONE_VENDOR_ENTRY;

        void applyTo(final MockHttpServletRequest request) {
            switch (this) {
                case CALLER_ACTION -> request.addHeader(ACTION_HEADER, "results-store.get-share-payload");
                case VENDOR_CONTENT_TYPE -> request.setContentType(VENDOR);
                case VENDOR_ACCEPT -> request.addHeader("Accept", VENDOR);
                case ACCEPT_LIST_WITH_ONE_VENDOR_ENTRY -> request.addHeader("Accept",
                        "application/json, " + VENDOR + ";q=0.9");
            }
        }
    }

    static Stream<Arguments> routesAndAttempts() {
        return Arrays.stream(ApiRoute.values())
                .flatMap(route -> Arrays.stream(Attempt.values()).map(attempt -> Arguments.of(route, attempt)));
    }

    private static MockHttpServletRequest requestFor(final ApiRoute route) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", samplePath(route));
        if (needsStoredAfterSeq(route)) {
            request.setParameter("storedAfterSeq", "0");
        }
        return request;
    }

    private record Outcome(HttpServletRequest seen, MockHttpServletResponse response) {
    }

    private Outcome filter(final HttpServletRequest request) throws ServletException, IOException {
        final MockFilterChain chain = new MockFilterChain();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        actionFilter.doFilter(request, response, chain);
        return new Outcome((HttpServletRequest) chain.getRequest(), response);
    }

    private static JsonNode body(final MockHttpServletResponse response) throws IOException {
        return MAPPER.readTree(response.getContentAsString());
    }

    /** A refusal is counted only once its body has been written: a client that has gone is not counted. */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "/results-store/v1/anything"})
    void a_refusal_whose_body_cannot_be_written_should_not_be_counted(final String methodOrPath) {
        final MockHttpServletRequest request = methodOrPath.startsWith("/")
                ? new MockHttpServletRequest("GET", methodOrPath)
                : new MockHttpServletRequest(methodOrPath, samplePath(ApiRoute.GET_SHARE));

        assertThatThrownBy(() -> actionFilter.doFilter(request, new BrokenPipeResponse(), new MockFilterChain()))
                .isInstanceOf(IOException.class).hasMessage(BrokenPipeResponse.BROKEN_PIPE);
        assertThat(observer.refusals()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("routesAndAttempts")
    void every_route_should_carry_its_derived_action_whatever_the_caller_sent(final ApiRoute route,
                                                                              final Attempt attempt)
            throws ServletException, IOException {
        final MockHttpServletRequest request = requestFor(route);
        attempt.applyTo(request);

        final Outcome outcome = filter(request);

        assertThat(outcome.seen()).isNotNull();
        assertThat(outcome.seen().getHeader(ACTION_HEADER)).isEqualTo(route.action());
        assertThat(Collections.list(outcome.seen().getHeaders(ACTION_HEADER))).containsExactly(route.action());
        assertThat(RequestActionResolver.resolve(outcome.seen(), ACTION_HEADER, samplePath(route)).name())
                .isEqualTo(route.action());
        assertThat(outcome.seen().getAttribute(ApiRoute.REQUEST_ATTRIBUTE)).isEqualTo(route);
        assertThat(observer.refusals()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("routesAndAttempts")
    void every_route_should_answer_application_json_for_a_vendor_content_type_or_accept(final ApiRoute route,
                                                                                        final Attempt attempt)
            throws ServletException, IOException {
        final MockHttpServletRequest request = requestFor(route);
        attempt.applyTo(request);

        final HttpServletRequest seen = filter(request).seen();

        assertThat(RequestActionResolver.resolve(seen, ACTION_HEADER, samplePath(route)).vendorSupplied()).isFalse();
        assertThat(Stream.of(seen.getContentType(), seen.getHeader("Content-Type"), seen.getHeader("Accept")))
                .allSatisfy(value -> assertThat(value).satisfiesAnyOf(
                        v -> assertThat(v).isNull(), v -> assertThat(v).isEqualTo(JSON)));
        assertThat(Collections.list(seen.getHeaders("Accept"))).allSatisfy(value -> assertThat(value).isEqualTo(JSON));
    }

    @Test
    void pull_and_search_should_derive_different_actions() throws ServletException, IOException {
        final MockHttpServletRequest pull = new MockHttpServletRequest("GET", "/results-store/v1/shares");
        pull.setParameter("storedAfterSeq", "12");
        final MockHttpServletRequest search = new MockHttpServletRequest("GET", "/results-store/v1/shares");
        search.setParameter("courtCentreId", "2b3c4d5e-0000-4000-8000-000000000002");

        assertThat(filter(pull).seen().getHeader(ACTION_HEADER)).isEqualTo("results-store.pull-shares");
        assertThat(filter(search).seen().getHeader(ACTION_HEADER)).isEqualTo("results-store.search-shares");
    }

    @Test
    void stored_after_seq_should_be_looked_up_only_after_the_method_and_path_match()
            throws ServletException, IOException {
        final MockHttpServletRequest unknown = spy(new MockHttpServletRequest("POST", "/results-store/v1/anything"));
        final MockHttpServletRequest known = spy(new MockHttpServletRequest("POST", "/results-store/v1/shares"));

        filter(unknown);
        filter(known);

        for (final MockHttpServletRequest request : List.of(unknown, known)) {
            verify(request, never()).getParameter(anyString());
            verify(request, never()).getParameterValues(anyString());
            verify(request, never()).getParameterMap();
            verify(request, never()).getParameterNames();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/results-store/v1/anything", "/results-store/v1/shares/", "/", "/favicon.ico",
        "/results-store/v1/shares;x=1", "/actuatorx", "/errors"})
    void an_unmapped_path_should_be_refused_404_route_not_found_without_calling_the_chain(final String path)
            throws ServletException, IOException {
        final Outcome outcome = filter(new MockHttpServletRequest("GET", path));

        assertThat(outcome.seen()).isNull();
        assertThat(outcome.response().getStatus()).isEqualTo(404);
        assertThat(outcome.response().getContentType()).isEqualTo("application/problem+json");
        assertThat(body(outcome.response()).get("reason").asString()).isEqualTo("route_not_found");
    }

    static Stream<Arguments> routesAndOtherMethods() {
        return Arrays.stream(ApiRoute.values()).flatMap(route -> Stream.of("HEAD", "OPTIONS", "POST", "PUT",
                "DELETE", "PATCH", "TRACE").map(method -> Arguments.of(route, method)));
    }

    @ParameterizedTest
    @MethodSource("routesAndOtherMethods")
    void a_known_path_with_another_method_including_head_and_options_should_be_refused_405_with_allow_get(
            final ApiRoute route, final String method) throws ServletException, IOException {
        final Outcome outcome = filter(new MockHttpServletRequest(method, samplePath(route)));

        assertThat(outcome.seen()).isNull();
        assertThat(outcome.response().getStatus()).isEqualTo(405);
        assertThat(outcome.response().getHeader("Allow")).isEqualTo("GET");
        assertThat(outcome.response().getContentType()).isEqualTo("application/problem+json");
        assertThat(body(outcome.response()).get("reason").asString()).isEqualTo("method_not_allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/health", "/actuator/health/readiness", "/actuator/prometheus",
        "/error"})
    void actuator_and_error_should_pass_with_cpp_action_removed_and_media_types_untouched(final String path)
            throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader(ACTION_HEADER, "results-store.get-share-payload");
        request.addHeader("Accept", VENDOR);
        request.setContentType(VENDOR);

        final Outcome outcome = filter(request);

        assertThat(outcome.seen()).isNotNull();
        assertThat(outcome.seen().getHeader(ACTION_HEADER)).isNull();
        assertThat(Collections.list(outcome.seen().getHeaders(ACTION_HEADER))).isEmpty();
        assertThat(Collections.list(outcome.seen().getHeaderNames()))
                .noneMatch(ACTION_HEADER::equalsIgnoreCase);
        assertThat(outcome.seen().getHeader("Accept")).isEqualTo(VENDOR);
        assertThat(outcome.seen().getContentType()).isEqualTo(VENDOR);
        assertThat(outcome.seen().getAttribute(ApiRoute.REQUEST_ATTRIBUTE)).isNull();
        assertThat(outcome.response().getStatus()).isEqualTo(200);
        assertThat(observer.refusals()).isEmpty();
    }

    @Test
    void the_refusal_body_should_be_the_four_fields_and_never_contain_the_requested_path()
            throws ServletException, IOException {
        final String secret = "zq-secret-segment";
        final MockHttpServletResponse notFound = filter(
                new MockHttpServletRequest("GET", "/results-store/v1/" + secret)).response();
        final MockHttpServletResponse notAllowed = filter(
                new MockHttpServletRequest("DELETE", "/results-store/v1/shares/" + secret)).response();

        assertThat(notFound.getContentAsString()).doesNotContain(secret).doesNotContain("results-store/v1");
        assertThat(notAllowed.getContentAsString()).doesNotContain(secret).doesNotContain("results-store/v1");
        final JsonNode notFoundBody = body(notFound);
        assertThat(notFoundBody.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "reason");
        assertThat(notFoundBody.get("type").asString()).isEqualTo("about:blank");
        assertThat(notFoundBody.get("title").asString()).isEqualTo("Not Found");
        assertThat(notFoundBody.get("status").asInt()).isEqualTo(404);
        final JsonNode notAllowedBody = body(notAllowed);
        assertThat(notAllowedBody.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "reason");
        assertThat(notAllowedBody.get("title").asString()).isEqualTo("Method Not Allowed");
        assertThat(notAllowedBody.get("status").asInt()).isEqualTo(405);
    }

    @Test
    void every_refusal_should_be_counted_once_with_its_reason() throws ServletException, IOException {
        filter(new MockHttpServletRequest("GET", "/results-store/v1/anything"));
        filter(new MockHttpServletRequest("OPTIONS", "/results-store/v1/shares"));
        filter(new MockHttpServletRequest("HEAD", samplePath(ApiRoute.GET_SHARE_PAYLOAD)));
        filter(requestFor(ApiRoute.GET_SHARE));
        filter(new MockHttpServletRequest("GET", "/actuator/health"));

        assertThat(observer.refusals()).containsExactly(RouteRefusal.ROUTE_NOT_FOUND,
                RouteRefusal.METHOD_NOT_ALLOWED, RouteRefusal.METHOD_NOT_ALLOWED);
    }

    @Test
    void a_context_path_should_be_left_out_of_the_match() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/ctx" + samplePath(ApiRoute.GET_SHARE));
        request.setContextPath("/ctx");

        assertThat(filter(request).seen().getHeader(ACTION_HEADER)).isEqualTo(ApiRoute.GET_SHARE.action());
        assertThat(Set.copyOf(observer.refusals())).isEmpty();
    }
}
