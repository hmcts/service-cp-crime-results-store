package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadForm;

/**
 * A payload as served (FR-033 to FR-035): the exact bytes, their {@code ETag} and the store's own facts for the
 * {@code Results-Store-*} headers.
 *
 * @param body              the bytes of the body, UTF-8 JSON without {@code _metadata}
 * @param etag              the strong, quoted {@code ETag}: the lower-case SHA-256 hex of exactly {@code body}
 * @param shareId           the share
 * @param hearingId         its hearing
 * @param hearingDay        its hearing day
 * @param sharedTime        its {@code shared_at}
 * @param enrichmentApplied whether intake added application results
 * @param form              the stored form the body was read from
 */
public record ServedPayload(byte[] body, String etag, UUID shareId, UUID hearingId, LocalDate hearingDay,
        Instant sharedTime, boolean enrichmentApplied, PayloadForm form) {

    /** Keeps its own copy of the bytes. */
    public ServedPayload {
        body = Objects.requireNonNull(body, "body").clone();
    }

    /** A copy of the bytes. */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** The body's length in bytes. */
    public int length() {
        return body.length;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof ServedPayload that && Arrays.equals(body, that.body) && etag.equals(that.etag)
                && shareId.equals(that.shareId) && hearingId.equals(that.hearingId)
                && hearingDay.equals(that.hearingDay) && sharedTime.equals(that.sharedTime)
                && enrichmentApplied == that.enrichmentApplied && form == that.form;
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(body) + Objects.hash(etag, shareId, hearingId, hearingDay, sharedTime,
                enrichmentApplied, form);
    }

    @Override
    public String toString() {
        // Never the body: identifiers, the length and the ETag only.
        return "ServedPayload[shareId=" + shareId + ", bytes=" + body.length + ", etag=" + etag + ", form=" + form
                + "]";
    }
}
