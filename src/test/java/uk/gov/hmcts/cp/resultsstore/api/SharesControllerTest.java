package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.BadParameterException;
import uk.gov.hmcts.cp.resultsstore.application.NotFoundException;
import uk.gov.hmcts.cp.resultsstore.application.PullPage;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.application.SearchPage;
import uk.gov.hmcts.cp.resultsstore.application.ShareReadService;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;
import uk.gov.hmcts.cp.resultsstore.openapi.api.SharesApi;
import uk.gov.hmcts.cp.resultsstore.openapi.model.ProblemDetail;
import uk.gov.hmcts.cp.resultsstore.support.ShareViews;

/** Pull, search and one share through the generated interface (FR-004 to FR-006, FR-010 to FR-031, FR-042). */
@WebMvcTest(SharesController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("shares controller")
class SharesControllerTest {

    /** The share item's fields (contracts/read-api.md §3); MAPPER member order is not part of the contract. */
    static final List<String> ITEM_FIELDS = List.of("shareId", "hearingId", "hearingDay", "sharedTime", "storedSeq",
            "storedAt", "sharedDayLondon", "sharedDayUtc", "keyDetails", "anySubjectIsYouth", "dayYouthSeen",
            "isLatest", "predecessorShareId", "arrivedOutOfOrder", "enrichmentApplied", "projectionStatus",
            "projectionVersion", "projectedAt", "versionNumber");

    static final List<String> KEY_DETAILS_FIELDS = List.of("courtCentreId", "courtRoomId", "ljaCode",
            "jurisdictionType", "isSjp", "isGroupProceedings", "youthCourtId", "isReshare");

    private static final String SHARES = "/results-store/v1/shares";

    private static final String COURT = "2b3c4d5e-0000-4000-8000-000000000002";

    private static final String CANARY = "zz-canary-zz";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RequestMappingHandlerMapping mappings;

    @MockitoBean
    private ShareReadService service;

    @MockitoBean
    private ReadObserver observer;

    private JsonNode json(final MockHttpServletResponse response) throws Exception {
        return MAPPER.readTree(response.getContentAsString());
    }

    private static List<String> names(final JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    @Test
    void the_controller_should_be_the_only_sharesapi_implementation_and_register_each_mapping_once() {
        final ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(SharesApi.class));
        final List<String> implementations = scanner.findCandidateComponents("uk.gov.hmcts.cp.resultsstore").stream()
                .map(BeanDefinition::getBeanClassName).toList();

        final Map<RequestMappingInfo, HandlerMethod> shares = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> SharesApi.class.isAssignableFrom(entry.getValue().getBeanType()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(implementations).containsExactly(SharesController.class.getName());
        assertThat(shares).hasSize(5);
        assertThat(shares.values()).extracting(HandlerMethod::getBeanType).containsOnly(SharesController.class);
        assertThat(shares.keySet()).flatExtracting(info -> info.getPathPatternsCondition().getPatternValues())
                .containsExactlyInAnyOrder(SharesApi.PATH_PULL_OR_SEARCH_SHARES, SharesApi.PATH_GET_SHARE,
                        SharesApi.PATH_GET_SHARE_PAYLOAD, SharesApi.PATH_LIST_HEARING_DAY_SHARES,
                        SharesApi.PATH_GET_SHARE_ARRIVED_PAYLOAD);
    }

    @Test
    void pull_should_map_every_parameter_and_field() throws Exception {
        when(service.pull(5L, 10, DayYouthFilter.NOT_FALSE, ShareViews.COURT_CENTRE_ID))
                .thenReturn(new PullPage(List.of(ShareViews.complete()), 48_213L, false,
                        Instant.parse("2026-10-03T18:00:04Z")));

        final MockHttpServletResponse response = mvc.perform(get(SHARES)
                .queryParam("storedAfterSeq", "5").queryParam("limit", "10").queryParam("dayYouthSeen", "notFalse")
                .queryParam("courtCentreId", COURT)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/json");
        final JsonNode page = json(response);
        assertThat(page.get("nextStoredAfterSeq").asLong()).isEqualTo(48_213L);
        assertThat(page.get("hasMore").asBoolean()).isFalse();
        assertThat(page.get("visibleUpTo").asString()).isEqualTo("2026-10-03T18:00:04.000000Z");
        final JsonNode item = page.get("items").get(0);
        assertThat(item.get("shareId").asString()).isEqualTo(ShareViews.SHARE_ID.toString());
        assertThat(item.get("hearingDay").asString()).isEqualTo("2026-10-02");
        assertThat(item.get("sharedDayUtc").asString()).isEqualTo("2026-10-01");
        assertThat(item.get("storedSeq").asLong()).isEqualTo(48_213L);
        assertThat(item.get("isLatest").asBoolean()).isTrue();
        assertThat(item.get("projectionStatus").asString()).isEqualTo("OK");
        assertThat(item.get("versionNumber").asInt()).isEqualTo(2);
        assertThat(item.get("keyDetails").get("ljaCode").asString()).isEqualTo("2577");
        assertThat(item.get("keyDetails").get("isGroupProceedings").asBoolean()).isTrue();
    }

    @Test
    void pull_should_default_the_limit_and_pass_no_filter() throws Exception {
        when(service.pull(0L, 100, null, null)).thenReturn(new PullPage(List.of(), 0L, false, Instant.EPOCH));

        assertThat(mvc.perform(get(SHARES).queryParam("storedAfterSeq", "0")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        verify(service).pull(0L, 100, null, null);
    }

    @Test
    void search_should_map_every_parameter_and_field() throws Exception {
        when(service.search(any())).thenReturn(new SearchPage(List.of(ShareViews.complete()), "djF8next"));

        final MockHttpServletResponse day = mvc.perform(get(SHARES).queryParam("courtCentreId", COURT)
                .queryParam("sharedDayFrom", "2026-10-01").queryParam("sharedDayTo", "2026-10-03")
                .queryParam("dayYouthSeen", "false").queryParam("latestOnly", "true").queryParam("limit", "7")
                .queryParam("cursor", "djF8prev")).andReturn().getResponse();
        final MockHttpServletResponse time = mvc.perform(get(SHARES).queryParam("courtCentreId", COURT)
                .queryParam("sharedFrom", "2026-10-02T23:00:00Z").queryParam("sharedTo", "2026-10-03T17:00:00.5Z"))
                .andReturn().getResponse();

        final ArgumentCaptor<ShareReadService.SearchRequest> requests =
                ArgumentCaptor.forClass(ShareReadService.SearchRequest.class);
        verify(service, times(2)).search(requests.capture());
        assertThat(requests.getAllValues()).containsExactly(
                new ShareReadService.SearchRequest(ShareViews.COURT_CENTRE_ID, LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-03"), null, null, DayYouthFilter.FALSE, true, 7, "djF8prev"),
                new ShareReadService.SearchRequest(ShareViews.COURT_CENTRE_ID, null, null,
                        Instant.parse("2026-10-02T23:00:00Z"), Instant.parse("2026-10-03T17:00:00.5Z"), null, false,
                        100, null));
        assertThat(day.getStatus()).isEqualTo(200);
        assertThat(time.getStatus()).isEqualTo(200);
        assertThat(json(day).get("nextCursor").asString()).isEqualTo("djF8next");
        assertThat(names(json(day).get("items").get(0))).containsExactlyInAnyOrderElementsOf(ITEM_FIELDS);
    }

    @Test
    void the_item_should_write_every_field_with_nulls_present() throws Exception {
        when(service.share(ShareViews.SHARE_ID)).thenReturn(ShareViews.failed());

        final JsonNode failed = json(mvc.perform(get(SHARES + "/" + ShareViews.SHARE_ID)).andReturn().getResponse());

        assertThat(names(failed)).containsExactlyInAnyOrderElementsOf(ITEM_FIELDS);
        assertThat(failed.get("keyDetails").isNull()).isTrue();
        assertThat(failed.get("anySubjectIsYouth").isNull()).isTrue();
        assertThat(failed.get("dayYouthSeen").isNull()).isTrue();
        assertThat(failed.get("predecessorShareId").isNull()).isTrue();
        assertThat(failed.get("projectionStatus").asString()).isEqualTo("FAILED");

        when(service.share(ShareViews.SHARE_ID)).thenReturn(ShareViews.complete());
        final JsonNode complete = json(mvc.perform(get(SHARES + "/" + ShareViews.SHARE_ID)).andReturn().getResponse());
        assertThat(names(complete.get("keyDetails"))).containsExactlyInAnyOrderElementsOf(KEY_DETAILS_FIELDS);
    }

    @Test
    void pull_and_search_should_be_written_as_their_own_page_shape() throws Exception {
        when(service.pull(0L, 100, null, null)).thenReturn(new PullPage(List.of(), 3L, false, Instant.EPOCH));
        when(service.search(any())).thenReturn(new SearchPage(List.of(), null));

        final JsonNode pull = json(mvc.perform(get(SHARES).queryParam("storedAfterSeq", "0")).andReturn()
                .getResponse());
        final JsonNode search = json(mvc.perform(get(SHARES).queryParam("courtCentreId", COURT)
                .queryParam("sharedDayFrom", "2026-10-01").queryParam("sharedDayTo", "2026-10-01")).andReturn()
                .getResponse());

        assertThat(names(pull)).containsExactlyInAnyOrder("items", "nextStoredAfterSeq", "hasMore", "visibleUpTo");
        assertThat(names(search)).containsExactlyInAnyOrder("items", "nextCursor");
        assertThat(search.get("nextCursor").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?storedAfterSeq=" + CANARY, "?storedAfterSeq=0&" + CANARY + "=1",
        "?courtCentreId=" + CANARY + "&sharedDayFrom=2026-10-01&sharedDayTo=2026-10-01", "/" + CANARY,
        "/" + CANARY + "/payload"})
    void no_problem_body_should_echo_a_caller_value(final String rest) throws Exception {
        final MockHttpServletResponse response = mvc.perform(get(SHARES + rest)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(names(json(response))).containsExactlyInAnyOrder("type", "title", "status", "reason");
        assertThat(response.getContentAsString()).doesNotContain(CANARY).doesNotContain("/results-store");
        verifyNoInteractions(service);
    }

    @Test
    void every_problem_body_should_read_as_the_generated_problem_detail() throws Exception {
        when(service.share(ShareViews.SHARE_ID)).thenThrow(new NotFoundException(ProblemReason.SHARE_NOT_FOUND));
        when(service.pull(0L, 100, null, null)).thenThrow(new BadParameterException(ProblemReason.INVALID_CURSOR));

        final ProblemDetail notFound = MAPPER.readValue(mvc.perform(get(SHARES + "/" + ShareViews.SHARE_ID))
                .andReturn().getResponse().getContentAsString(), ProblemDetail.class);
        final MockHttpServletResponse badResponse = mvc.perform(get(SHARES).queryParam("storedAfterSeq", "0"))
                .andReturn().getResponse();
        final ProblemDetail bad = MAPPER.readValue(badResponse.getContentAsString(), ProblemDetail.class);

        assertThat(notFound).isEqualTo(ProblemDetail.builder().type(ProblemDetail.TypeEnum.ABOUT_BLANK)
                .title("Not Found").status(404).reason(ProblemDetail.ReasonEnum.SHARE_NOT_FOUND).build());
        assertThat(bad).isEqualTo(ProblemDetail.builder().type(ProblemDetail.TypeEnum.ABOUT_BLANK)
                .title("Bad Request").status(400).reason(ProblemDetail.ReasonEnum.INVALID_CURSOR).build());
        assertThat(badResponse.getContentType()).isEqualTo("application/problem+json");
    }

    /**
     * The controller's own guard, without the interceptor that refuses the value first in every slice: a value
     * the interceptor let through still never reaches the service.
     */
    @Test
    void the_controller_should_refuse_a_bad_day_youth_seen_itself_without_calling_the_service() {
        final SharesController controller = new SharesController(service);

        assertThatThrownBy(() -> controller.pullOrSearchShares(0L, 100, "maybe", null, null, null, null, null, null,
                null)).isInstanceOfSatisfying(BadParameterException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(ProblemReason.INVALID_DAY_YOUTH_SEEN));
        verifyNoInteractions(service);
    }
}
