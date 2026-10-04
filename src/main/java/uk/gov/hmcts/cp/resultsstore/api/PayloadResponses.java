package uk.gov.hmcts.cp.resultsstore.api;

import java.util.Arrays;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.cp.resultsstore.application.ServedPayload;

/**
 * The payload's {@code 200} (FR-033 to FR-037; research R10, R11): the exact bytes as a {@code byte[]}, written by
 * {@code ByteArrayHttpMessageConverter}, never parsed or re-written; a strong {@code ETag} over them, from which
 * Spring answers {@code If-None-Match} with a {@code 304} carrying exactly one {@code ETag}; the store's own facts
 * as {@code Results-Store-*} headers; {@code Cache-Control: no-store}; {@code Content-Type: application/json}
 * with no charset; a {@code Content-Length} of the byte count.
 */
public final class PayloadResponses {

    /** {@code Results-Store-Share-Id}. */
    public static final String SHARE_ID = "Results-Store-Share-Id";

    /** {@code Results-Store-Hearing-Id}. */
    public static final String HEARING_ID = "Results-Store-Hearing-Id";

    /** {@code Results-Store-Hearing-Day}. */
    public static final String HEARING_DAY = "Results-Store-Hearing-Day";

    /** {@code Results-Store-Shared-Time}. */
    public static final String SHARED_TIME = "Results-Store-Shared-Time";

    /** {@code Results-Store-Enrichment-Applied}. */
    public static final String ENRICHMENT_APPLIED = "Results-Store-Enrichment-Applied";

    /** {@code Results-Store-Payload-Form}. */
    public static final String PAYLOAD_FORM = "Results-Store-Payload-Form";

    private PayloadResponses() {
        // Static functions only.
    }

    /**
     * The answer to a payload request: {@code 304} with the {@code ETag} and {@code Cache-Control: no-store} when
     * {@code If-None-Match} is {@code *} (the share exists, so its current representation matches; Spring's own check honours {@code *}
     * only for unsafe methods), else {@link #served}, from whose {@code ETag} Spring answers any other
     * {@code If-None-Match} (research R11).
     *
     * @param payload     the bytes and their facts
     * @param ifNoneMatch the request's {@code If-None-Match}, or {@code null}
     * @return the response
     */
    public static ResponseEntity<byte[]> answer(final ServedPayload payload, final String ifNoneMatch) {
        return anyIsStar(ifNoneMatch)
                ? ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(payload.etag())
                        .cacheControl(CacheControl.noStore()).build()
                : served(payload);
    }

    /**
     * The {@code 200} for a served payload.
     *
     * @param payload the bytes and their facts
     * @return the response, body the exact bytes
     */
    public static ResponseEntity<byte[]> served(final ServedPayload payload) {
        return ResponseEntity.ok()
                .eTag(payload.etag())
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(payload.length())
                .cacheControl(CacheControl.noStore())
                .header(SHARE_ID, payload.shareId().toString())
                .header(HEARING_ID, payload.hearingId().toString())
                .header(HEARING_DAY, payload.hearingDay().toString())
                .header(SHARED_TIME, InstantFormat.format(payload.sharedTime()))
                .header(ENRICHMENT_APPLIED, Boolean.toString(payload.enrichmentApplied()))
                .header(PAYLOAD_FORM, payload.form().headerValue())
                .body(payload.body());
    }

    private static boolean anyIsStar(final String ifNoneMatch) {
        return ifNoneMatch != null && Arrays.stream(ifNoneMatch.split(",")).map(String::trim).anyMatch("*"::equals);
    }
}
