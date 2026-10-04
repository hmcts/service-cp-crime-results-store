package uk.gov.hmcts.cp.resultsstore.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.server.RequestPath;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;
import uk.gov.hmcts.cp.resultsstore.filters.QueryParameterNames;
import uk.gov.hmcts.cp.resultsstore.filters.RefusalWriter;

/**
 * Runs {@link ShareParameters} on a read route after the handler is chosen and before Spring binds its
 * arguments (research R23 C3): a refused request is answered with the bounded problem body here, and the handler
 * is never called. The route is the one the action filter matched, or, where no filter ran, the one the method
 * and path resolve to.
 */
public class ShareParametersInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(final HttpServletRequest request, final HttpServletResponse response,
            final Object handler) throws IOException {
        final Optional<ProblemReason> refusal = route(request)
                .flatMap(route -> ShareParameters.check(route, request.getQueryString(), pathVariables(request)));
        if (refusal.isPresent()) {
            RefusalWriter.write(response, refusal.get());
        }
        return refusal.isEmpty();
    }

    private static Optional<ApiRoute> route(final HttpServletRequest request) {
        return request.getAttribute(ApiRoute.REQUEST_ATTRIBUTE) instanceof ApiRoute matched
                ? Optional.of(matched)
                : ApiRoute.resolve(request.getMethod(),
                        RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication(),
                        name -> QueryParameterNames.contains(request.getQueryString(), name));
    }

    @SuppressWarnings("unchecked") // Spring MVC's documented type for this attribute.
    private static Map<String, String> pathVariables(final HttpServletRequest request) {
        final Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> ? (Map<String, String>) variables : Map.of();
    }
}
