package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/**
 * Why a request was refused before it reached the audit filter: the {@code reason} tag of
 * {@code resultsstore.read.refused} (contracts/metrics.md).
 */
public enum RouteRefusal {

    /** A path the service does not serve: {@code 404 route_not_found}, before authorisation. */
    ROUTE_NOT_FOUND,
    /** A served path with another method: {@code 405 method_not_allowed}, before authorisation. */
    METHOD_NOT_ALLOWED,
    /** A {@code multipart/*} request on a route: {@code 415 unsupported_content_type}, after authorisation. */
    UNSUPPORTED_CONTENT_TYPE;

    /** The {@code reason} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
