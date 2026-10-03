package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.springframework.http.HttpRequest;

/**
 * One lookup's request as the transport sees it, created with the request by
 * {@link NoRedirectRequestFactory} and carried in the request's attributes, so the client can end the
 * exchange without reading what is left of it.
 */
public final class ProgressionExchange {

    /** The request attribute that holds the exchange. */
    /* default */ static final String ATTRIBUTE = ProgressionExchange.class.getName();

    private final HttpUriRequestBase request;

    /* default */ ProgressionExchange(final HttpUriRequestBase request) {
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
}
