package uk.gov.hmcts.cp.resultsstore.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.web.servlet.HandlerInterceptor;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.domain.ReadOutcome;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;

/**
 * Records {@code resultsstore.read.requests} and {@code resultsstore.read.duration} when a read request completes
 * (contracts/metrics.md; research R17): the endpoint from the route the action filter matched, the outcome from
 * the final status (so a {@code 304} Spring decides after the handler, and an exception the advice answered, are
 * counted by what was sent), the duration from the handler's start. A request with no matched route records
 * nothing: the action filter refused it and counted it. A request refused before its handler was chosen (a
 * {@code 406} from content negotiation) never reaches this interceptor; {@link ReadApiExceptionHandler} counts it
 * instead, and {@link #claimCount} makes sure only one of the two ever counts a request.
 */
public class ReadMetricsInterceptor implements HandlerInterceptor {

    private static final String STARTED = ReadMetricsInterceptor.class.getName() + ".started";

    private static final String COUNTED = ReadMetricsInterceptor.class.getName() + ".counted";

    private final ReadObserver observer;

    private final LongSupplier nanoClock;

    /**
     * Creates the interceptor.
     *
     * @param observer  the read meters
     * @param nanoClock a nanosecond clock
     */
    public ReadMetricsInterceptor(final ReadObserver observer, final LongSupplier nanoClock) {
        this.observer = observer;
        this.nanoClock = nanoClock;
    }

    @Override
    public boolean preHandle(final HttpServletRequest request, final HttpServletResponse response,
            final Object handler) {
        request.setAttribute(STARTED, nanoClock.getAsLong());
        return true;
    }

    @Override
    public void afterCompletion(final HttpServletRequest request, final HttpServletResponse response,
            final Object handler, final Exception exception) {
        if (request.getAttribute(ApiRoute.REQUEST_ATTRIBUTE) instanceof ApiRoute route
                && request.getAttribute(STARTED) instanceof Long started && claimCount(request)) {
            observer.request(route.endpoint(), ReadOutcome.forStatus(response.getStatus()),
                    Duration.ofNanos(nanoClock.getAsLong() - started));
        }
    }

    /**
     * Whether this interceptor's {@link #preHandle} ran for the request, that is, whether a handler was chosen.
     *
     * @param request the request
     * @return {@code true} once the handler has been chosen
     */
    /* default */ static boolean handlerStarted(final HttpServletRequest request) {
        return request.getAttribute(STARTED) instanceof Long;
    }

    /**
     * Claims the request's single count in {@code resultsstore.read.requests}.
     *
     * @param request the request
     * @return {@code true} for the first caller only
     */
    /* default */ static boolean claimCount(final HttpServletRequest request) {
        final boolean first = request.getAttribute(COUNTED) == null;
        request.setAttribute(COUNTED, Boolean.TRUE);
        return first;
    }
}
