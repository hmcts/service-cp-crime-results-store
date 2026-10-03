package uk.gov.hmcts.cp.resultsstore.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.RefusalObserver;
import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;

/**
 * The service's {@code /error} page (FR-043, research R13): one handler for every method and every
 * {@code Accept}, {@code text/html} included, writing the four-field body: as {@code application/json} for a
 * {@code 401} or {@code 403} and as {@code application/problem+json} for any other status (contracts/read-api.md
 * §6). As an
 * {@link ErrorController} bean it makes Boot's {@code BasicErrorController}, whose HTML handler would answer
 * {@code Accept: text/html} with no view, back off. The body is written here, with no content negotiation, so
 * no {@code Accept} can turn it into a {@code 406} or an HTML page.
 *
 * <p>The authorisation library's {@code sendError(401)} and {@code sendError(403)} land here, so this is
 * where they are counted in {@code resultsstore.read.refused} ({@code unauthenticated}, {@code forbidden}),
 * once the body has been written and flushed; no other status is counted. Registered by {@code ApiWebConfig}, not component-scanned, behind a URL
 * mapping of its own.
 */
public class BoundedErrorController implements ErrorController, Controller {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ErrorAttributes errorAttributes;

    private final RefusalObserver refusals;

    /**
     * Creates the controller.
     *
     * @param errorAttributes builds the bounded body
     * @param refusals counts the {@code 401} and {@code 403} refusals
     */
    public BoundedErrorController(final ErrorAttributes errorAttributes, final RefusalObserver refusals) {
        this.errorAttributes = errorAttributes;
        this.refusals = refusals;
    }

    /**
     * Writes the bounded body for the error dispatch.
     *
     * @param request the error dispatch
     * @param response the response
     * @return null: the response is complete
     * @throws IOException when the body cannot be written
     */
    @Override
    public ModelAndView handleRequest(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException {
        final Map<String, Object> body = errorAttributes.getErrorAttributes(new ServletWebRequest(request),
                ErrorAttributeOptions.defaults());
        final int status = ((Number) body.get("status")).intValue();
        final byte[] bytes = MAPPER.writeValueAsBytes(body);
        response.setStatus(status);
        response.setContentType(mediaType(status));
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
        count(ProblemReason.forErrorStatus(status));
        return null;
    }

    /** contracts/read-api.md §6: {@code 401} and {@code 403} as {@code application/json}, the rest as problem JSON. */
    private static String mediaType(final int status) {
        return status == HttpServletResponse.SC_UNAUTHORIZED || status == HttpServletResponse.SC_FORBIDDEN
                ? MediaType.APPLICATION_JSON_VALUE : MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    }

    private void count(final ProblemReason reason) {
        if (reason == ProblemReason.UNAUTHENTICATED) {
            refusals.refused(RouteRefusal.UNAUTHENTICATED);
        } else if (reason == ProblemReason.FORBIDDEN) {
            refusals.refused(RouteRefusal.FORBIDDEN);
        }
    }
}
