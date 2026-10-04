package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.NotFoundException;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.application.ShareReadService;
import uk.gov.hmcts.cp.resultsstore.support.ShareViews;

/** A hearing day's versions (FR-032). */
@WebMvcTest(SharesController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("hearing day shares controller")
class HearingDaySharesControllerTest {

    private static final String DAY = "/results-store/v1/hearings/" + ShareViews.HEARING_ID + "/days/2026-10-02/shares";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ShareReadService service;

    @MockitoBean
    private ReadObserver observer;

    @Test
    void should_list_in_shared_at_order() throws Exception {
        final UUID first = UUID.fromString("00000000-0000-4000-8000-000000000009");
        final UUID second = UUID.fromString("00000000-0000-4000-8000-000000000001");
        when(service.dayVersions(ShareViews.HEARING_ID, ShareViews.HEARING_DAY))
                .thenReturn(List.of(ShareViews.complete(first, 20L, 1), ShareViews.complete(second, 10L, 2)));

        final MockHttpServletResponse response = mvc.perform(get(DAY)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/json");
        final JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(new ArrayList<>(body.propertyNames())).containsExactly("items");
        assertThat(body.get("items").valueStream().map(item -> item.get("shareId").asString()))
                .containsExactly(first.toString(), second.toString());
        assertThat(body.get("items").valueStream().map(item -> item.get("versionNumber").asInt()))
                .containsExactly(1, 2);
    }

    @Test
    void an_empty_day_should_be_404_hearing_day_not_found() throws Exception {
        when(service.dayVersions(ShareViews.HEARING_ID, ShareViews.HEARING_DAY))
                .thenThrow(new NotFoundException(ProblemReason.HEARING_DAY_NOT_FOUND));

        final MockHttpServletResponse response = mvc.perform(get(DAY)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(JsonMapper.builder().build().readTree(response.getContentAsString())).isEqualTo(
                JsonMapper.builder().build().readTree(
                        "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"reason\":\"hearing_day_not_found\"}"));
    }

    @Test
    void a_bad_day_or_hearing_should_be_refused_with_its_own_reason() throws Exception {
        assertThat(mvc.perform(get("/results-store/v1/hearings/" + ShareViews.HEARING_ID + "/days/2026-02-30/shares"))
                .andReturn().getResponse().getContentAsString()).contains("\"invalid_hearing_day\"");
        assertThat(mvc.perform(get("/results-store/v1/hearings/1-1-1-1-1/days/2026-10-02/shares"))
                .andReturn().getResponse().getContentAsString()).contains("\"invalid_hearing_id\"");
    }
}
