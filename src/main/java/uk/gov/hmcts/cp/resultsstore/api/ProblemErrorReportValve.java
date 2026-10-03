package uk.gov.hmcts.cp.resultsstore.api;

import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.RefusalObserver;
import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;

/**
 * The host's error report (contracts/read-api.md §6), in place of Tomcat's HTML page. It writes only for an
 * error nothing else has answered: in practice a request the HTTP connector rejects before it reaches the
 * service (an encoded slash or backslash, a NUL, a malformed escape), which never reaches the service's
 * {@code /error} page. The body is the four fields from the status alone ({@code bad_request} for a {@code 4xx},
 * {@code internal_error} for a {@code 5xx}); never the URI, an exception or server details. A {@code 4xx}
 * so answered is the connector's refusal: it is counted as {@code connector_rejected} in
 * {@code resultsstore.read.refused}, once its body has been written. A body that cannot be written is not
 * counted, and a {@code 5xx} is the server's failure, not a refusal.
 */
public class ProblemErrorReportValve extends ErrorReportValve {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorReportValve.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final int ERRORS_FROM = 400;

    private static final int SERVER_ERRORS_FROM = 500;

    private final RefusalObserver refusals;

    /**
     * Creates the valve.
     *
     * @param refusals counts a connector-level refusal once its report is written
     */
    public ProblemErrorReportValve(final RefusalObserver refusals) {
        super();
        this.refusals = refusals;
    }

    @Override
    protected void report(final Request request, final Response response, final Throwable throwable) {
        if (response.getStatus() >= ERRORS_FROM && response.getContentWritten() == 0 && response.setErrorReported()) {
            final int status = BoundedErrorAttributes.errorStatus(response.getStatus());
            final byte[] body = MAPPER.writeValueAsBytes(BoundedErrorAttributes.problemBody(status));
            boolean written = false;
            try {
                response.setStatus(status);
                response.setContentType(BoundedErrorController.mediaType(status));
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
                response.finishResponse();
                written = true;
            } catch (IOException | IllegalStateException e) {
                // The client has gone or the response cannot take a body: nothing more can be sent.
                LOG.warn("Could not write the error report for status {}: {}", status, e.getClass().getSimpleName());
            }
            if (written) {
                countRefusal(status);
            }
        }
    }

    private void countRefusal(final int status) {
        if (status < SERVER_ERRORS_FROM) {
            refusals.refused(RouteRefusal.CONNECTOR_REJECTED);
        }
    }
}
