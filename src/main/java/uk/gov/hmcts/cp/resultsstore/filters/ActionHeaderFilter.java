package uk.gov.hmcts.cp.resultsstore.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.gov.hmcts.cp.resultsstore.api.ProblemReason;
import uk.gov.hmcts.cp.resultsstore.application.RefusalObserver;
import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;

/**
 * Derives the {@code CPP-ACTION} that {@code cp-auth-rules-filter} authorises from the request's method and
 * path, for every request (research R2, FR-046 to FR-048). Registered first by {@code ApiWebConfig}.
 *
 * <ul>
 *   <li>A mapped route: the request goes on wrapped ({@link ActionRequestWrapper#forRoute}), carrying the
 *       route's action whatever the caller sent, with vendor media types answered as JSON; the matched
 *       {@link ApiRoute} is left in the request attribute {@link ApiRoute#REQUEST_ATTRIBUTE}, and the
 *       {@code Content-Type} as sent in {@link #SENT_CONTENT_TYPE_ATTRIBUTE}.</li>
 *   <li>A known path with another method ({@code HEAD} and {@code OPTIONS} included):
 *       {@code 405 method_not_allowed} with {@code Allow}. The authorisation library always lets
 *       {@code OPTIONS} through, so it is refused here.</li>
 *   <li>Any other path except {@code /actuator/**} and {@code /error}: {@code 404 route_not_found}; the
 *       chain is not called.</li>
 *   <li>{@code /actuator/**} and {@code /error}: passed on with {@code CPP-ACTION} removed and media types
 *       untouched.</li>
 * </ul>
 *
 * <p>Every refusal is counted once through the {@link RefusalObserver}; refused requests never reach the
 * audit filter. The body names the reason only, never the path.
 */
public class ActionHeaderFilter extends OncePerRequestFilter {

    /**
     * The request attribute under which a mapped route's {@code Content-Type} is left as the caller sent it,
     * before {@link ActionRequestWrapper} neutralises vendor tokens: the {@code 415} guard classifies that.
     */
    public static final String SENT_CONTENT_TYPE_ATTRIBUTE = ActionHeaderFilter.class.getName() + ".sentContentType";

    private static final String ALLOW = "Allow";

    private static final String ACTUATOR = "/actuator";

    private static final String ERROR = "/error";

    private final RefusalObserver refusals;

    /**
     * Creates the filter.
     *
     * @param refusals counts the refusals
     */
    public ActionHeaderFilter(final RefusalObserver refusals) {
        super();
        this.refusals = refusals;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        final PathContainer path = RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication();
        if (passesThrough(path.value())) {
            filterChain.doFilter(ActionRequestWrapper.withoutAction(request), response);
        } else {
            final List<String> allowed = ApiRoute.allowedMethods(path);
            if (allowed.isEmpty()) {
                refuse(response, RouteRefusal.ROUTE_NOT_FOUND, ProblemReason.ROUTE_NOT_FOUND);
            } else if (allowed.contains(request.getMethod())) {
                final ApiRoute route = ApiRoute.resolve(request.getMethod(), path,
                        name -> request.getParameter(name) != null).orElseThrow();
                request.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, route);
                request.setAttribute(SENT_CONTENT_TYPE_ATTRIBUTE, request.getContentType());
                filterChain.doFilter(ActionRequestWrapper.forRoute(request, route), response);
            } else {
                response.setHeader(ALLOW, String.join(", ", allowed));
                refuse(response, RouteRefusal.METHOD_NOT_ALLOWED, ProblemReason.METHOD_NOT_ALLOWED);
            }
        }
    }

    private void refuse(final HttpServletResponse response, final RouteRefusal refusal, final ProblemReason reason)
            throws IOException {
        refusals.refused(refusal);
        RefusalWriter.write(response, reason);
    }

    private static boolean passesThrough(final String path) {
        return ACTUATOR.equals(path) || path.startsWith(ACTUATOR + "/") || ERROR.equals(path);
    }
}
