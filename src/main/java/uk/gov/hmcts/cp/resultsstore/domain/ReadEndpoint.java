package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/**
 * A read endpoint: the {@code endpoint} tag of {@code resultsstore.read.requests} and
 * {@code resultsstore.read.duration} (contracts/metrics.md). One constant per route of the read API.
 */
public enum ReadEndpoint {

    /** {@code GET /results-store/v1/shares?storedAfterSeq=…}: the feed. */
    PULL,
    /** {@code GET /results-store/v1/shares} without {@code storedAfterSeq}: a court's shares over a range. */
    SEARCH,
    /** {@code GET /results-store/v1/shares/{shareId}}. */
    SHARE,
    /** {@code GET /results-store/v1/shares/{shareId}/payload}. */
    PAYLOAD,
    /** {@code GET /results-store/v1/hearings/{hearingId}/days/{hearingDay}/shares}. */
    DAY_VERSIONS,
    /** {@code GET /results-store/v1/shares/{shareId}/payload/arrived}: the text as it arrived (phase D). */
    ARRIVED_PAYLOAD;

    /** The {@code endpoint} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
