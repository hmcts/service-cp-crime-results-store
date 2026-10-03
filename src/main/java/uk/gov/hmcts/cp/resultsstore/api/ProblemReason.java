package uk.gov.hmcts.cp.resultsstore.api;

import java.util.Locale;

/**
 * The single table from a problem body's {@code reason} to its HTTP status (contracts/read-api.md §6).
 * The status is a plain {@code int}, so no HTTP type comes with this enum where the application layer
 * names a reason (research R1).
 */
public enum ProblemReason {

    UNKNOWN_PARAMETER(400),
    REPEATED_PARAMETER(400),
    CONFLICTING_PARAMETERS(400),
    MISSING_PARAMETER(400),
    INVALID_STORED_AFTER_SEQ(400),
    LIMIT_OUT_OF_RANGE(400),
    INVALID_DAY_YOUTH_SEEN(400),
    INVALID_COURT_CENTRE_ID(400),
    INVALID_SHARED_DAY(400),
    DAY_RANGE_REVERSED(400),
    DAY_RANGE_TOO_LONG(400),
    INVALID_SHARED_FROM(400),
    INVALID_SHARED_TO(400),
    TIME_RANGE_REVERSED(400),
    TIME_RANGE_TOO_LONG(400),
    INVALID_LATEST_ONLY(400),
    INVALID_CURSOR(400),
    INVALID_SHARE_ID(400),
    INVALID_HEARING_ID(400),
    INVALID_HEARING_DAY(400),
    BAD_REQUEST(400),
    UNAUTHENTICATED(401),
    FORBIDDEN(403),
    ROUTE_NOT_FOUND(404),
    SHARE_NOT_FOUND(404),
    HEARING_DAY_NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    NOT_ACCEPTABLE(406),
    UNSUPPORTED_CONTENT_TYPE(415),
    INTERNAL_ERROR(500),
    STORE_UNAVAILABLE(503);

    private static final int CLIENT_ERRORS_FROM = 400;

    private static final int SERVER_ERRORS_FROM = 500;

    private final int httpStatus;

    ProblemReason(final int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** The HTTP status the reason is sent with. */
    public int status() {
        return httpStatus;
    }

    /**
     * The reason {@code /error} gives a status it did not choose itself (research R13): {@code 401}
     * {@code unauthenticated}, {@code 403} {@code forbidden}, any other {@code 4xx} {@code bad_request},
     * anything else {@code internal_error}.
     *
     * @param status the status the error dispatch carries
     * @return the bounded reason
     */
    public static ProblemReason forErrorStatus(final int status) {
        final ProblemReason reason;
        if (status == UNAUTHENTICATED.httpStatus) {
            reason = UNAUTHENTICATED;
        } else if (status == FORBIDDEN.httpStatus) {
            reason = FORBIDDEN;
        } else if (status >= CLIENT_ERRORS_FROM && status < SERVER_ERRORS_FROM) {
            reason = BAD_REQUEST;
        } else {
            reason = INTERNAL_ERROR;
        }
        return reason;
    }

    /** The {@code reason} field: the constant's name in lower snake case. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
