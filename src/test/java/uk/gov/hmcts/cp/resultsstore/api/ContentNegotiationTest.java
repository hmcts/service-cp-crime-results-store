package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.server.RequestPath;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.NotFoundException;
import uk.gov.hmcts.cp.resultsstore.application.PullPage;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.application.SearchPage;
import uk.gov.hmcts.cp.resultsstore.application.ServedPayload;
import uk.gov.hmcts.cp.resultsstore.application.ShareReadService;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadForm;
import uk.gov.hmcts.cp.resultsstore.domain.ReadOutcome;
import uk.gov.hmcts.cp.resultsstore.filters.ActionRequestWrapper;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;
import uk.gov.hmcts.cp.resultsstore.filters.QueryParameterNames;
import uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples;
import uk.gov.hmcts.cp.resultsstore.support.ShareViews;

/**
 * What each {@code Accept} gives on each route once {@link ActionRequestWrapper} has read a vendor type as JSON
 * (research R23 C5): JSON, {@code *}{@code /*}, none, a vendor type and {@code application/problem+json} all get
 * {@code 200} as {@code application/json}; {@code text/html} gets {@code 406 not_acceptable}.
 */
@WebMvcTest(SharesController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("content negotiation")
class ContentNegotiationTest {

    private static final String SEARCH_QUERY = "?courtCentreId=" + ShareViews.COURT_CENTRE_ID
            + "&sharedDayFrom=2026-10-01&sharedDayTo=2026-10-01";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private ShareReadService service;

    @MockitoBean
    private ReadObserver observer;

    private MockMvc mvc;

    @BeforeEach
    void wrapLikeTheActionFilter() {
        final Filter wrap = (request, response, chain) -> {
            final HttpServletRequest http = (HttpServletRequest) request;
            final ApiRoute route = ApiRoute.resolve(http.getMethod(),
                    RequestPath.parse(http.getRequestURI(), http.getContextPath()).pathWithinApplication(),
                    name -> QueryParameterNames.contains(http.getQueryString(), name)).orElseThrow();
            http.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, route);
            chain.doFilter(ActionRequestWrapper.forRoute(http, route), response);
        };
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilter(wrap).build();
        when(service.pull(0L, 100, null, null)).thenReturn(new PullPage(List.of(), 0L, false, Instant.EPOCH));
        when(service.search(any())).thenReturn(new SearchPage(List.of(), null));
        when(service.share(ShareViews.SHARE_ID)).thenReturn(ShareViews.complete());
        when(service.dayVersions(ShareViews.HEARING_ID, ShareViews.HEARING_DAY)).thenReturn(
                List.of(ShareViews.complete()));
        when(service.payload(ShareViews.SHARE_ID)).thenReturn(new ServedPayload(
                "{}".getBytes(StandardCharsets.UTF_8), "\"44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\"",
                ShareViews.SHARE_ID, ShareViews.HEARING_ID, ShareViews.HEARING_DAY, Instant.EPOCH, false,
                PayloadForm.WORKING_COPY));
        when(service.arrivedPayload(ShareViews.SHARE_ID)).thenReturn(new ServedPayload(
                "{}".getBytes(StandardCharsets.UTF_8), "\"44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\"",
                ShareViews.SHARE_ID, ShareViews.HEARING_ID, ShareViews.HEARING_DAY, Instant.EPOCH, false,
                PayloadForm.ARRIVED_TEXT));
    }

    private static String path(final ApiRoute route) {
        return ApiRouteSamples.samplePath(route) + switch (route) {
            case PULL_SHARES -> "?storedAfterSeq=0";
            case SEARCH_SHARES -> SEARCH_QUERY;
            default -> "";
        };
    }

    private MockHttpServletResponse call(final ApiRoute route, final String accept) throws Exception {
        final MockHttpServletRequestBuilder request = get(path(route));
        return mvc.perform(accept == null ? request : request.header("Accept", accept)).andReturn().getResponse();
    }

    static Stream<Arguments> routesAndAccepts() {
        return Stream.of(ApiRoute.values()).flatMap(route -> Stream.of("application/json", "*/*", null,
                        "application/vnd.results-store.anything+json", "application/vnd.usersgroups.get-groups+json",
                        "application/problem+json")
                .map(accept -> Arguments.of(route, accept)));
    }

    @ParameterizedTest
    @MethodSource("routesAndAccepts")
    void accept_json_star_absent_and_vendor_should_give_200_application_json(final ApiRoute route,
            final String accept) throws Exception {
        final MockHttpServletResponse response = call(route, accept);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/json");
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void accept_problem_json_should_give_200_with_content_type_application_json(final ApiRoute route)
            throws Exception {
        final MockHttpServletResponse response = call(route, "application/problem+json");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/json");
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void accept_text_html_should_give_406_not_acceptable_with_the_bounded_body(final ApiRoute route)
            throws Exception {
        final MockHttpServletResponse response = call(route, "text/html");

        assertThat(response.getStatus()).isEqualTo(406);
        assertThat(JSON.readTree(response.getContentAsString())).isEqualTo(JSON.readTree(
                "{\"type\":\"about:blank\",\"title\":\"Not Acceptable\",\"status\":406,\"reason\":\"not_acceptable\"}"));
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void accept_text_html_should_be_counted_once_as_bad_request(final ApiRoute route) throws Exception {
        call(route, "text/html");

        verify(observer).requestWithoutHandler(route.endpoint(), ReadOutcome.BAD_REQUEST);
        verify(observer, never()).request(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ApiRoute.class, names = {"GET_SHARE", "GET_SHARE_PAYLOAD", "GET_SHARE_ARRIVED_PAYLOAD"})
    void an_error_should_be_application_json_whatever_accept_says(final ApiRoute route) throws Exception {
        when(service.share(ShareViews.SHARE_ID)).thenThrow(new NotFoundException(ProblemReason.SHARE_NOT_FOUND));
        when(service.payload(ShareViews.SHARE_ID)).thenThrow(new NotFoundException(ProblemReason.SHARE_NOT_FOUND));
        when(service.arrivedPayload(ShareViews.SHARE_ID))
                .thenThrow(new NotFoundException(ProblemReason.SHARE_NOT_FOUND));

        for (final String accept : new String[] {"application/json", "application/problem+json", "*/*",
            "application/vnd.x+json"}) {
            final MockHttpServletResponse response = call(route, accept);
            assertThat(response.getStatus()).as(accept).isEqualTo(404);
            assertThat(response.getContentType()).as(accept).isEqualTo("application/problem+json");
            assertThat(response.getContentAsString()).as(accept).contains("\"share_not_found\"");
        }
    }

    /** Sanity: without the wrapper a vendor type is not one the mappings produce. */
    @ParameterizedTest
    @EnumSource(value = ApiRoute.class, names = "GET_SHARE")
    void without_the_wrapper_a_vendor_accept_would_be_refused(final ApiRoute route) throws Exception {
        final MockHttpServletRequest unwrapped = get(path(route)).header("Accept", "application/vnd.x+json")
                .buildRequest(context.getServletContext());
        final MockMvc bare = MockMvcBuilders.webAppContextSetup(context).build();

        assertThat(bare.perform(request -> unwrapped).andReturn().getResponse().getStatus()).isEqualTo(406);
    }
}
