package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.springframework.http.HttpRequest;

/**
 * One lookup's request as the transport sees it, created with the request by
 * {@link NoRedirectRequestFactory} and carried in the request's attributes. It holds the lookup's one
 * absolute deadline, fixed when the request was created and shared by the factory's cancellation and
 * the body guard ({@link DeadlineInputStream}), and records whether that cancellation ended the
 * exchange: only then is a failed read a timeout. The client can also end the exchange itself, without
 * reading what is left of it.
 */
public final class ProgressionExchange {

    /** The request attribute that holds the exchange. */
    /* default */ static final String ATTRIBUTE = ProgressionExchange.class.getName();

    private final long deadlineNanos;

    private final HttpUriRequestBase request;

    private final AtomicBoolean expired = new AtomicBoolean();

    /* default */ ProgressionExchange(final long deadlineNanos, final HttpUriRequestBase request) {
        this.deadlineNanos = deadlineNanos;
        this.request = request;
    }

    /**
     * The exchange of a request built by {@link NoRedirectRequestFactory}.
     *
     * @param request the request
     * @return its exchange
     * @throws IllegalStateException when another factory built the request
     */
    public static ProgressionExchange exchangeOf(final HttpRequest request) {
        if (!(request.getAttributes().get(ATTRIBUTE) instanceof ProgressionExchange exchange)) {
            throw new IllegalStateException("request not built by NoRedirectRequestFactory");
        }
        return exchange;
    }

    /**
     * Ends the exchange now, closing its connection, so closing the response reads nothing more of
     * the body. Doing it to a finished exchange does nothing.
     */
    public void abort() {
        request.cancel();
    }

    /**
     * The deadline, on the {@code System.nanoTime()} scale.
     *
     * @return the deadline in nanoseconds
     */
    public long getDeadlineNanos() {
        return deadlineNanos;
    }

    /**
     * Whether the deadline cancelled the exchange.
     *
     * @return true once the factory's scheduler has cancelled it
     */
    public boolean isExpired() {
        return expired.get();
    }

    /** The deadline has passed: recorded first, then the exchange is cancelled. */
    /* default */ void expire() {
        expired.set(true);
        request.cancel();
    }
}
