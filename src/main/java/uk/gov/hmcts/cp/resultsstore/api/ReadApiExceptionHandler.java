package uk.gov.hmcts.cp.resultsstore.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uk.gov.hmcts.cp.resultsstore.application.BadParameterException;
import uk.gov.hmcts.cp.resultsstore.application.NotFoundException;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.domain.CanonicalUuid;
import uk.gov.hmcts.cp.resultsstore.domain.ReadOutcome;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;
import uk.gov.hmcts.cp.resultsstore.openapi.model.ProblemDetail;

/**
 * Every error the read API's handlers raise, as the four-field problem body and nothing else (FR-042 to FR-044;
 * research R13): {@code application/problem+json}, written as the contract jar's generated {@link ProblemDetail}.
 * Spring MVC's own exceptions come through {@link #handleExceptionInternal}, so none is rendered with Spring's
 * {@code detail} or {@code instance}; {@code spring.mvc.problemdetails.enabled} stays off.
 *
 * <ul>
 *   <li>{@link BadParameterException} and {@link NotFoundException}: their own reason.</li>
 *   <li>A type mismatch on a parameter (a value {@link ShareParametersInterceptor} let through and Spring could
 *       not bind): that parameter's own reason.</li>
 *   <li>The database unreachable or a query timed out: {@code 503 store_unavailable} with
 *       {@code Retry-After: 5}.</li>
 *   <li>Anything else, an unreadable stored text included: {@code 500 internal_error}, logged by exception class
 *       with the {@code shareId} in the logging context when the path names one; never the message.</li>
 *   <li>A request on a matched route that fails before its handler is chosen (a {@code 406} from content
 *       negotiation) is counted here in {@code resultsstore.read.requests}, since {@link ReadMetricsInterceptor}
 *       never sees it (contracts/metrics.md).</li>
 *   <li>One of Spring MVC's own exceptions once the response is committed: logged by class, nothing written.</li>
 * </ul>
 */
@RestControllerAdvice
public class ReadApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** The seconds a caller waits before trying again after a {@code 503}. */
    public static final String RETRY_AFTER_SECONDS = "5";

    private static final Logger LOG = LoggerFactory.getLogger(ReadApiExceptionHandler.class);

    private static final String SHARE_ID = "shareId";

    private static final String FAILURE = "Read request failed: {}";

    private static final String COMMITTED_FAILURE = "Read request failed after the response was committed: {}";

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    /** Each typed parameter's own reason, for a value Spring could not bind. */
    private static final Map<String, ProblemReason> PARAMETER_REASONS = Map.ofEntries(
            Map.entry("storedAfterSeq", ProblemReason.INVALID_STORED_AFTER_SEQ),
            Map.entry("limit", ProblemReason.LIMIT_OUT_OF_RANGE),
            Map.entry("courtCentreId", ProblemReason.INVALID_COURT_CENTRE_ID),
            Map.entry("sharedDayFrom", ProblemReason.INVALID_SHARED_DAY),
            Map.entry("sharedDayTo", ProblemReason.INVALID_SHARED_DAY),
            Map.entry("sharedFrom", ProblemReason.INVALID_SHARED_FROM),
            Map.entry("sharedTo", ProblemReason.INVALID_SHARED_TO),
            Map.entry("latestOnly", ProblemReason.INVALID_LATEST_ONLY),
            Map.entry(SHARE_ID, ProblemReason.INVALID_SHARE_ID),
            Map.entry("hearingId", ProblemReason.INVALID_HEARING_ID),
            Map.entry("hearingDay", ProblemReason.INVALID_HEARING_DAY));

    private final ReadObserver observer;

    /**
     * Creates the advice.
     *
     * @param observer the read meters, for a request refused before its handler was chosen
     */
    public ReadApiExceptionHandler(final ReadObserver observer) {
        super();
        this.observer = observer;
    }

    /**
     * The body and headers of a refusal.
     *
     * @param reason  the bounded reason
     * @param headers headers to send with it
     * @return the response
     */
    public static ResponseEntity<Object> problem(final ProblemReason reason, final HttpHeaders headers) {
        final HttpStatus status = HttpStatus.valueOf(reason.status());
        final ProblemDetail body = ProblemDetail.builder()
                .type(ProblemDetail.TypeEnum.ABOUT_BLANK)
                .title(status.getReasonPhrase())
                .status(reason.status())
                .reason(ProblemDetail.ReasonEnum.fromValue(reason.code()))
                .build();
        return ResponseEntity.status(status).headers(headers).contentType(PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(BadParameterException.class)
    public ResponseEntity<Object> badParameter(final BadParameterException exception) {
        return problem(exception.reason(), new HttpHeaders());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Object> notFound(final NotFoundException exception) {
        return problem(exception.reason(), new HttpHeaders());
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class})
    public ResponseEntity<Object> storeUnavailable(final RuntimeException exception) {
        LOG.warn("Read request could not reach the store: {}", exception.getClass().getName());
        final HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        return problem(ProblemReason.STORE_UNAVAILABLE, headers);
    }

    /**
     * Anything else: {@code 500 internal_error}.
     *
     * @param exception the failure
     * @param request   the request, for the {@code shareId} its path names
     * @return the response
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Object> internalError(final RuntimeException exception, final HttpServletRequest request) {
        countWithoutHandler(request, HttpStatus.INTERNAL_SERVER_ERROR);
        logFailure(FAILURE, exception, request);
        return problem(ProblemReason.INTERNAL_ERROR, new HttpHeaders());
    }

    /**
     * Spring MVC's own exceptions, as the four-field body. When the response is already committed nothing can be
     * appended to it: the failure is logged by exception class and nothing is returned, as Spring's own handler
     * does.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(final Exception exception, final Object body,
            final HttpHeaders headers, final HttpStatusCode statusCode, final WebRequest request) {
        final ServletWebRequest servlet = request instanceof ServletWebRequest servletRequest ? servletRequest : null;
        final HttpServletRequest servletRequest = servlet == null ? null : servlet.getRequest();
        final HttpServletResponse response = servlet == null ? null : servlet.getResponse();
        if (servletRequest != null) {
            countWithoutHandler(servletRequest, statusCode);
        }
        final ResponseEntity<Object> answer;
        if (response != null && response.isCommitted()) {
            logFailure(COMMITTED_FAILURE, exception, servletRequest);
            answer = null;
        } else {
            if (statusCode.is5xxServerError()) {
                // Spring's own 5xx (a response it could not write, a missing path variable) is a failure like any
                // other.
                logFailure(FAILURE, exception, servletRequest);
            }
            answer = problem(reason(exception, statusCode), headers);
        }
        return answer;
    }

    /**
     * Counts a request on a matched route that failed before Spring MVC chose its handler (a {@code 406} from
     * content negotiation), which {@link ReadMetricsInterceptor} never sees; once only, and never one the
     * interceptor counts.
     */
    private void countWithoutHandler(final HttpServletRequest request, final HttpStatusCode statusCode) {
        if (request.getAttribute(ApiRoute.REQUEST_ATTRIBUTE) instanceof ApiRoute route
                && !ReadMetricsInterceptor.handlerStarted(request) && ReadMetricsInterceptor.claimCount(request)) {
            observer.requestWithoutHandler(route.endpoint(), ReadOutcome.forStatus(statusCode.value()));
        }
    }

    /** Logs a failure by its class, with the canonical {@code shareId} in the logging context when there is one. */
    private static void logFailure(final String message, final Exception exception,
            final HttpServletRequest request) {
        final String shareId = request == null ? null : shareId(request);
        try (MDC.MDCCloseable ignored = shareId == null ? null : MDC.putCloseable(SHARE_ID, shareId)) {
            LOG.error(message, exception.getClass().getName());
        }
    }

    /** The reason for one of Spring MVC's own exceptions. */
    private static ProblemReason reason(final Exception exception, final HttpStatusCode statusCode) {
        final ProblemReason reason;
        if (exception instanceof HttpRequestMethodNotSupportedException) {
            reason = ProblemReason.METHOD_NOT_ALLOWED;
        } else if (exception instanceof HttpMediaTypeNotAcceptableException) {
            reason = ProblemReason.NOT_ACCEPTABLE;
        } else if (exception instanceof NoResourceFoundException) {
            reason = ProblemReason.ROUTE_NOT_FOUND;
        } else if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
            reason = PARAMETER_REASONS.getOrDefault(mismatch.getName(), ProblemReason.BAD_REQUEST);
        } else {
            reason = ProblemReason.forErrorStatus(statusCode.value());
        }
        return reason;
    }

    /** The {@code shareId} path variable when the path names a canonical one; never any other value. */
    private static String shareId(final HttpServletRequest request) {
        final Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        final Object value = variables instanceof Map<?, ?> map ? map.get(SHARE_ID) : null;
        return value instanceof String text ? CanonicalUuid.parse(text).map(Object::toString).orElse(null) : null;
    }
}
