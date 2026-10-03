package uk.gov.hmcts.cp.resultsstore.filters;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import uk.gov.hmcts.cp.resultsstore.domain.ReadEndpoint;

/**
 * The read API's route table (research R2): one constant per (method, path template, action, endpoint
 * tag). It is the single source for {@link ActionHeaderFilter}, the allow rules' tests and the OpenAPI
 * document's tests.
 *
 * <p>Templates are compiled with Spring's {@link PathPatternParser#defaultInstance}, the matcher Spring
 * MVC routes with: case-sensitive, decoding per segment, no optional trailing slash. A path carrying a
 * {@code ;parameter} on any segment matches no route, so the service never serves a path the
 * authorisation library and MVC could read differently. Pull and search share one template and are told
 * apart by whether {@code storedAfterSeq} is present, which is asked only once the method and path have
 * matched.
 */
public enum ApiRoute {

    /** {@code GET /results-store/v1/shares?storedAfterSeq=…}. */
    PULL_SHARES(ApiRoute.SHARES, "results-store.pull-shares", ReadEndpoint.PULL),
    /** {@code GET /results-store/v1/shares} without {@code storedAfterSeq}. */
    SEARCH_SHARES(ApiRoute.SHARES, "results-store.search-shares", ReadEndpoint.SEARCH),
    /** {@code GET /results-store/v1/shares/{shareId}}. */
    GET_SHARE(ApiRoute.SHARES + "/{shareId}", "results-store.get-share", ReadEndpoint.SHARE),
    /** {@code GET /results-store/v1/shares/{shareId}/payload}. */
    GET_SHARE_PAYLOAD(ApiRoute.SHARES + "/{shareId}/payload", "results-store.get-share-payload",
            ReadEndpoint.PAYLOAD),
    /** {@code GET /results-store/v1/hearings/{hearingId}/days/{hearingDay}/shares}. */
    LIST_HEARING_DAY_SHARES(ApiRoute.BASE + "/hearings/{hearingId}/days/{hearingDay}/shares",
            "results-store.list-hearing-day-shares", ReadEndpoint.DAY_VERSIONS);

    /** The request attribute under which {@link ActionHeaderFilter} leaves the matched route. */
    public static final String REQUEST_ATTRIBUTE = ApiRoute.class.getName();

    /** The query parameter whose presence makes {@code GET /shares} a pull. */
    public static final String STORED_AFTER_SEQ = "storedAfterSeq";

    private static final String BASE = "/results-store/v1";

    private static final String SHARES = BASE + "/shares";

    private static final String GET = "GET";

    private final String pathTemplate;

    private final String actionName;

    private final ReadEndpoint endpointTag;

    /** Not serialised: an enum constant is serialised by its name alone. */
    private final transient PathPattern pattern;

    ApiRoute(final String template, final String action, final ReadEndpoint endpoint) {
        this.pathTemplate = template;
        this.actionName = action;
        this.endpointTag = endpoint;
        this.pattern = PathPatternParser.defaultInstance.parse(template);
    }

    /** The one method the route serves. */
    public String method() {
        return GET;
    }

    /** The path template, as written in {@code results-store-openapi.yaml}. */
    public String template() {
        return pathTemplate;
    }

    /** The {@code CPP-ACTION} the allow rule names. */
    public String action() {
        return actionName;
    }

    /** The {@code endpoint} tag of the read meters. */
    public ReadEndpoint endpoint() {
        return endpointTag;
    }

    /**
     * Whether the path, within the application, is this route's template.
     *
     * @param path the path within the application
     * @return true when the template matches and no segment carries a {@code ;parameter}
     */
    public boolean matches(final PathContainer path) {
        return withoutSegmentParameters(path) && pattern.matches(path);
    }

    /**
     * The methods served on the path.
     *
     * @param path the path within the application
     * @return {@code GET} for a known path; empty for a path no route serves
     */
    public static List<String> allowedMethods(final PathContainer path) {
        return Arrays.stream(values())
                .filter(route -> route.matches(path))
                .map(ApiRoute::method)
                .distinct()
                .toList();
    }

    /**
     * The route a request is for.
     *
     * @param method the request's method
     * @param path the path within the application
     * @param parameterPresent whether the request carries a query parameter; asked only once the method
     *     and path have matched
     * @return the route, or empty when the method and path match none
     */
    public static Optional<ApiRoute> resolve(final String method, final PathContainer path,
                                             final Predicate<String> parameterPresent) {
        return Arrays.stream(values())
                .filter(route -> route.method().equals(method) && route.matches(path))
                .findFirst()
                .map(route -> SHARES.equals(route.pathTemplate) ? sharesRoute(parameterPresent) : route);
    }

    private static ApiRoute sharesRoute(final Predicate<String> parameterPresent) {
        return parameterPresent.test(STORED_AFTER_SEQ) ? PULL_SHARES : SEARCH_SHARES;
    }

    private static boolean withoutSegmentParameters(final PathContainer path) {
        return path.elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .noneMatch(segment -> segment.value().indexOf(';') >= 0);
    }
}
