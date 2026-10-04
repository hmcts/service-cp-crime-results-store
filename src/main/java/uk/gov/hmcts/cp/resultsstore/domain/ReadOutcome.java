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

    private static final int NOT_MODIFIED_STATUS = 304;

    private static final int CLIENT_ERRORS_FROM = 400;

    private static final int NOT_FOUND_STATUS = 404;

    private static final int SERVER_ERRORS_FROM = 500;

    private static final int UNAVAILABLE_STATUS = 503;

    /**
     * The outcome a response status stands for (contracts/metrics.md, *Outcome from status*).
     *
     * @param status the response's status
     * @return {@code 304} {@link #NOT_MODIFIED}; {@code 404} {@link #NOT_FOUND}; any other {@code 4xx}
     *         {@link #BAD_REQUEST}; {@code 503} {@link #UNAVAILABLE}; any other {@code 5xx} {@link #FAILED};
     *         anything else {@link #OK}
     */
    public static ReadOutcome forStatus(final int status) {
        final ReadOutcome outcome;
        if (status == NOT_MODIFIED_STATUS) {
            outcome = NOT_MODIFIED;
        } else if (status == NOT_FOUND_STATUS) {
            outcome = NOT_FOUND;
        } else if (status == UNAVAILABLE_STATUS) {
            outcome = UNAVAILABLE;
        } else if (status >= SERVER_ERRORS_FROM) {
            outcome = FAILED;
        } else if (status >= CLIENT_ERRORS_FROM) {
            outcome = BAD_REQUEST;
        } else {
            outcome = OK;
        }
        return outcome;
    }

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
