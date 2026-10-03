package uk.gov.hmcts.cp.resultsstore.api;

import jakarta.servlet.RequestDispatcher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * The {@code /error} body: {@code type}, {@code title}, {@code status} and {@code reason}, and nothing else
 * (FR-042, research R13). Boot's attributes would echo the request path, and can carry an exception's
 * message or trace; none of those is ever written, whatever the options ask for. The reason comes from the
 * status alone ({@link ProblemReason#forErrorStatus}). A dispatch with no error status, or one outside
 * {@code 4xx} and {@code 5xx}, is written as {@code 500 internal_error}.
 *
 * <p>Extends {@link DefaultErrorAttributes} so it still records a handler's exception for
 * {@link #getError}, as the bean Boot's own would replace.
 */
public class BoundedErrorAttributes extends DefaultErrorAttributes {

    /** The body's {@code type}. */
    public static final String TYPE = "about:blank";

    private static final int CLIENT_ERRORS_FROM = 400;

    private static final int ERRORS_TO = 599;

    @Override
    public Map<String, Object> getErrorAttributes(final WebRequest webRequest, final ErrorAttributeOptions options) {
        return problemBody(errorStatus(webRequest.getAttribute(RequestDispatcher.ERROR_STATUS_CODE,
                RequestAttributes.SCOPE_REQUEST)));
    }

    /**
     * The four fields for an error status: the title is the status's reason phrase, or the bounded reason's
     * own for a status Spring does not know.
     *
     * @param status a {@code 4xx} or {@code 5xx} status
     * @return {@code type}, {@code title}, {@code status} and {@code reason}, in that order
     */
    public static Map<String, Object> problemBody(final int status) {
        final ProblemReason reason = ProblemReason.forErrorStatus(status);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE);
        body.put("title", Optional.ofNullable(HttpStatus.resolve(status))
                .orElse(HttpStatus.valueOf(reason.status()))
                .getReasonPhrase());
        body.put("status", status);
        body.put("reason", reason.code());
        return body;
    }

    /* default */ static int errorStatus(final Object attribute) {
        final int status;
        if (attribute instanceof Integer code && code >= CLIENT_ERRORS_FROM && code <= ERRORS_TO) {
            status = code;
        } else {
            status = ProblemReason.INTERNAL_ERROR.status();
        }
        return status;
    }
}
