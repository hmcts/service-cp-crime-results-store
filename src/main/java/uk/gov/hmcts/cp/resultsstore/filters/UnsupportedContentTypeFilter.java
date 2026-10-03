package uk.gov.hmcts.cp.resultsstore.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.gov.hmcts.cp.resultsstore.api.ProblemReason;
import uk.gov.hmcts.cp.resultsstore.application.RefusalObserver;
import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;

/**
 * Refuses a {@code multipart/*} request on a read API route with {@code 415 unsupported_content_type}
 * (FR-048 (d)): no request to the API has a body. Registered by {@code ApiWebConfig} after the authorisation
 * library and before the audit filter, so the audit library never buffers a multipart body. A route is
 * recognised by the {@link ApiRoute} the action filter left on the request; {@code /actuator} and
 * {@code /error} carry none and are never refused. The refusal is counted and its body names the reason only.
 */
public class UnsupportedContentTypeFilter extends OncePerRequestFilter {

    private static final String MULTIPART = "multipart/";

    private final RefusalObserver refusals;

    /**
     * Creates the filter.
     *
     * @param refusals counts the refusals
     */
    public UnsupportedContentTypeFilter(final RefusalObserver refusals) {
        super();
        this.refusals = refusals;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        if (request.getAttribute(ApiRoute.REQUEST_ATTRIBUTE) != null && isMultipart(request.getContentType())) {
            refusals.refused(RouteRefusal.UNSUPPORTED_CONTENT_TYPE);
            RefusalWriter.write(response, ProblemReason.UNSUPPORTED_CONTENT_TYPE);
        } else {
            filterChain.doFilter(request, response);
        }
    }

    private static boolean isMultipart(final String contentType) {
        return contentType != null && contentType.strip().toLowerCase(Locale.ROOT).startsWith(MULTIPART);
    }
}
