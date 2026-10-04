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
 * {@code /error} carry none and are never refused. The media type classified is the {@code Content-Type} as
 * the caller sent it ({@link ActionHeaderFilter#SENT_CONTENT_TYPE_ATTRIBUTE}), not the wrapped request's: the
 * wrapper answers {@code application/json} for any value carrying a vendor token, a multipart parameter
 * included. The refusal is counted once its body has been written, and the body names the reason only.
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
        if (request.getAttribute(ApiRoute.REQUEST_ATTRIBUTE) != null
                && isMultipart(request.getAttribute(ActionHeaderFilter.SENT_CONTENT_TYPE_ATTRIBUTE))) {
            RefusalWriter.write(response, ProblemReason.UNSUPPORTED_CONTENT_TYPE);
            refusals.refused(RouteRefusal.UNSUPPORTED_CONTENT_TYPE);
        } else {
            filterChain.doFilter(request, response);
        }
    }

    private static boolean isMultipart(final Object contentType) {
        return contentType instanceof String sent && sent.strip().toLowerCase(Locale.ROOT).startsWith(MULTIPART);
    }
}
