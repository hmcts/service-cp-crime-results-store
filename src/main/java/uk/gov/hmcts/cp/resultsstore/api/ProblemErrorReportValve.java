package uk.gov.hmcts.cp.resultsstore.api;

import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The host's error report (contracts/read-api.md §6), in place of Tomcat's HTML page. It writes only for an
 * error nothing else has answered: in practice a request the HTTP connector rejects before it reaches the
 * service (an encoded slash or backslash, a NUL, a malformed escape), which never reaches the service's
 * {@code /error} page. The body is the four fields from the status alone ({@code bad_request} for a {@code 4xx},
 * {@code internal_error} for a {@code 5xx}); never the URI, an exception or server details. Such a refusal
 * is not counted: it carries no route and no reason of the service's own.
 */
public class ProblemErrorReportValve extends ErrorReportValve {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorReportValve.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final int ERRORS_FROM = 400;

    @Override
    protected void report(final Request request, final Response response, final Throwable throwable) {
        if (response.getStatus() >= ERRORS_FROM && response.getContentWritten() == 0 && response.setErrorReported()) {
            final int status = BoundedErrorAttributes.errorStatus(response.getStatus());
            final byte[] body = MAPPER.writeValueAsBytes(BoundedErrorAttributes.problemBody(status));
            try {
                response.setStatus(status);
                response.setContentType(BoundedErrorController.mediaType(status));
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
                response.finishResponse();
            } catch (IOException | IllegalStateException e) {
                // The client has gone or the response cannot take a body: nothing more can be sent.
                LOG.warn("Could not write the error report for status {}: {}", status, e.getClass().getSimpleName());
            }
        }
    }
}
