package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.application.ServedPayload;
import uk.gov.hmcts.cp.resultsstore.application.ShareReadService;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.support.ShareViews;

/** The payload as exact bytes with a strong ETag and its headers (FR-033 to FR-037; research R10, R11). */
@WebMvcTest(SharesController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("share payload controller")
class SharePayloadControllerTest {

    private static final String PAYLOAD_PATH = "/results-store/v1/shares/" + ShareViews.SHARE_ID + "/payload";

    /** Spacing, key order and escapes Jackson would change if it wrote the body: "é" and a raw "\u00e9". */
    private static final byte[] BODY = ("{\"hearing\": {\"id\": \"x\", \"note\": \"caf\u00e9 \\u00e9\"}, "
            + "\"isReshare\": false, \"hearingDay\": \"2026-10-02\", \"sharedTime\": 1.50}")
            .getBytes(StandardCharsets.UTF_8);

    private static final String ETAG = "\"" + PayloadChecksum.sha256Hex(BODY) + "\"";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ShareReadService service;

    @MockitoBean
    private ReadObserver observer;

    @BeforeEach
    void payload() {
        when(service.payload(ShareViews.SHARE_ID)).thenReturn(new ServedPayload(BODY, ETAG, ShareViews.SHARE_ID,
                ShareViews.HEARING_ID, ShareViews.HEARING_DAY, Instant.parse("2026-10-02T16:41:07.5Z"), true));
    }

    private MockHttpServletResponse fetch(final String ifNoneMatch) throws Exception {
        return mvc.perform(ifNoneMatch == null ? get(PAYLOAD_PATH) : get(PAYLOAD_PATH).header("If-None-Match", ifNoneMatch))
                .andReturn().getResponse();
    }

    @Test
    void the_body_should_be_byte_identical_to_the_service_bytes() throws Exception {
        final MockHttpServletResponse response = fetch(null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(BODY);
        assertThat(PayloadChecksum.sha256Hex(response.getContentAsByteArray()))
                .isEqualTo(ETAG.substring(1, ETAG.length() - 1));
    }

    @Test
    void the_body_should_be_written_by_the_byte_array_converter() throws Exception {
        final MockHttpServletResponse ranged = mvc.perform(get(PAYLOAD_PATH).header("Range", "bytes=0-9")).andReturn()
                .getResponse();

        assertThat(ranged.getStatus()).isEqualTo(200);
        assertThat(ranged.getHeader("Accept-Ranges")).isNull();
        assertThat(ranged.getHeader("Content-Range")).isNull();
        assertThat(ranged.getContentAsByteArray()).isEqualTo(BODY);
    }

    @Test
    void the_body_should_have_no_metadata_key() throws Exception {
        assertThat(JsonMapper.builder().build().readTree(fetch(null).getContentAsByteArray()).has("_metadata"))
                .isFalse();
    }

    @Test
    void the_etag_should_be_strong_and_quoted() throws Exception {
        assertThat(fetch(null).getHeader("ETag")).isEqualTo(ETAG).matches("\"[0-9a-f]{64}\"");
    }

    @Test
    void exactly_one_etag_header_should_be_sent_on_200_and_on_304() throws Exception {
        assertThat(fetch(null).getHeaders("ETag")).containsExactly(ETAG);
        assertThat(fetch(ETAG).getHeaders("ETag")).containsExactly(ETAG);
        assertThat(fetch("*").getHeaders("ETag")).containsExactly(ETAG);
    }

    @Test
    void identity_and_enrichment_headers_should_be_present() throws Exception {
        final MockHttpServletResponse response = fetch(null);

        assertThat(response.getHeader("Results-Store-Share-Id")).isEqualTo(ShareViews.SHARE_ID.toString());
        assertThat(response.getHeader("Results-Store-Hearing-Id")).isEqualTo(ShareViews.HEARING_ID.toString());
        assertThat(response.getHeader("Results-Store-Hearing-Day")).isEqualTo("2026-10-02");
        assertThat(response.getHeader("Results-Store-Shared-Time")).isEqualTo("2026-10-02T16:41:07.500000Z");
        assertThat(response.getHeader("Results-Store-Enrichment-Applied")).isEqualTo("true");
    }

    /** The form header went with the arrived text (spec 005 FR-006). */
    @Test
    void the_payload_should_carry_no_payload_form_header() throws Exception {
        assertThat(fetch(null).getHeaderNames()).doesNotContain("Results-Store-Payload-Form");
    }

    @Test
    void cache_control_should_be_no_store() throws Exception {
        assertThat(fetch(null).getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void content_type_should_be_application_json_without_charset() throws Exception {
        assertThat(fetch(null).getContentType()).isEqualTo("application/json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STRONG", "W/STRONG", "\"0000\", STRONG", "*"})
    void if_none_match_should_give_304_for_strong_weak_list_and_star(final String header) throws Exception {
        final MockHttpServletResponse response = fetch(header.replace("STRONG", ETAG));

        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.getContentAsByteArray()).isEmpty();
        // Every 304, Spring's or the star's, tells a cache not to keep it.
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void a_stale_if_none_match_should_give_200() throws Exception {
        final MockHttpServletResponse response = fetch("\"" + "0".repeat(64) + "\"");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(BODY);
    }

    @Test
    void no_content_encoding_and_content_length_should_equal_the_byte_count() throws Exception {
        final MockHttpServletResponse response = mvc.perform(get(PAYLOAD_PATH).header("Accept-Encoding", "gzip"))
                .andReturn().getResponse();

        assertThat(response.getHeader("Content-Encoding")).isNull();
        assertThat(response.getHeader("Transfer-Encoding")).isNull();
        assertThat(response.getHeaders("Content-Length")).isEqualTo(List.of(Integer.toString(BODY.length)));
        assertThat(response.getContentLength()).isEqualTo(BODY.length);
    }
}
