package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/**
 * How a read request ended: the {@code outcome} tag of {@code resultsstore.read.requests}
 * (contracts/metrics.md), worked out from the response status.
 */
public enum ReadOutcome {

    /** {@code 200}. */
    OK,
    /** {@code 304}: the payload endpoints only. */
    NOT_MODIFIED,
    /** Any {@code 4xx} but {@code 404}. */
    BAD_REQUEST,
    /** {@code 404}. */
    NOT_FOUND,
    /** {@code 503}. */
    UNAVAILABLE,
    /** Any {@code 5xx} but {@code 503}. */
    FAILED;

    /** The {@code outcome} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a request to the endpoint can end this way, so the pair is a registered series.
     *
     * @param endpoint the endpoint
     * @return false only for {@link #NOT_MODIFIED} on an endpoint that serves no payload
     */
    public boolean appliesTo(final ReadEndpoint endpoint) {
        return this != NOT_MODIFIED || endpoint == ReadEndpoint.PAYLOAD;
    }
}
