package uk.gov.hmcts.cp.resultsstore.support;

import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;

/** One concrete path per route of the read API, for the route, rule and filter tests. */
public final class ApiRouteSamples {

    public static final String SHARE_ID = "6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b";

    public static final String HEARING_ID = "1a2b3c4d-0000-4000-8000-000000000001";

    public static final String HEARING_DAY = "2026-10-02";

    private ApiRouteSamples() {
        // Static fixture holder.
    }

    /**
     * A path the route serves.
     *
     * @param route the route
     * @return a concrete path within the application, with no query
     */
    public static String samplePath(final ApiRoute route) {
        return switch (route) {
            case PULL_SHARES, SEARCH_SHARES -> "/results-store/v1/shares";
            case GET_SHARE -> "/results-store/v1/shares/" + SHARE_ID;
            case GET_SHARE_PAYLOAD -> "/results-store/v1/shares/" + SHARE_ID + "/payload";
            case LIST_HEARING_DAY_SHARES -> "/results-store/v1/hearings/" + HEARING_ID + "/days/" + HEARING_DAY
                    + "/shares";
        };
    }

    /**
     * A concrete path of a route with another template, for "right name, wrong path" cases.
     *
     * @param route the route
     * @return a path no route sharing this route's template serves
     */
    public static String anotherRoutesPath(final ApiRoute route) {
        return switch (route) {
            case PULL_SHARES, SEARCH_SHARES, GET_SHARE_PAYLOAD -> samplePath(ApiRoute.GET_SHARE);
            case GET_SHARE, LIST_HEARING_DAY_SHARES -> samplePath(ApiRoute.GET_SHARE_PAYLOAD);
        };
    }

    /**
     * Whether the route is served when {@code storedAfterSeq} is present (only pull and search differ).
     *
     * @param route the route
     * @return true for pull
     */
    public static boolean needsStoredAfterSeq(final ApiRoute route) {
        return route == ApiRoute.PULL_SHARES;
    }
}
