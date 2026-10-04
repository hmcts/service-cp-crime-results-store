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
 * nothing: the action filter refused it and counted it.
 */
public class ReadMetricsInterceptor implements HandlerInterceptor {

    private static final String STARTED = ReadMetricsInterceptor.class.getName() + ".started";

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
                && request.getAttribute(STARTED) instanceof Long started) {
            observer.request(route.endpoint(), ReadOutcome.forStatus(response.getStatus()),
                    Duration.ofNanos(nanoClock.getAsLong() - started));
        }
    }
}
