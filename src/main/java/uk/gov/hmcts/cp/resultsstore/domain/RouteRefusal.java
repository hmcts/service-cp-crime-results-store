package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/**
 * Why a request was refused before it reached the audit filter: the {@code reason} tag of
 * {@code resultsstore.read.refused} (contracts/metrics.md). The first three are this service's filters'
 * refusals; the next two the authorisation library's, counted by the service's error controller; the last the
 * HTTP connector's, counted by the host's error report.
 */
public enum RouteRefusal {

    /** A path the service does not serve: {@code 404 route_not_found}, before authorisation. */
    ROUTE_NOT_FOUND,
    /** A served path with another method: {@code 405 method_not_allowed}, before authorisation. */
    METHOD_NOT_ALLOWED,
    /** A {@code multipart/*} request on a route: {@code 415 unsupported_content_type}, after authorisation. */
    UNSUPPORTED_CONTENT_TYPE,
    /** No {@code CJSCPPUID}: the authorisation library's {@code 401}, counted at {@code /error}. */
    UNAUTHENTICATED,
    /** A caller in neither admitted group: the authorisation library's {@code 403}, counted at {@code /error}. */
    FORBIDDEN,
    /**
     * A request target the HTTP connector rejects before the service sees it: Tomcat's {@code 400}, answered
     * {@code bad_request} and counted by the host's error report once its body is written.
     */
    CONNECTOR_REJECTED;

    /** The {@code reason} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
