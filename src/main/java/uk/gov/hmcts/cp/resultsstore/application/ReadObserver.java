package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Duration;
import uk.gov.hmcts.cp.resultsstore.domain.ReadEndpoint;
import uk.gov.hmcts.cp.resultsstore.domain.ReadOutcome;

/** The read API's meters (contracts/metrics.md), apart from refusals ({@link RefusalObserver}). */
public interface ReadObserver {

    /**
     * Records one completed request: {@code resultsstore.read.requests} and {@code resultsstore.read.duration}.
     *
     * @param endpoint the endpoint
     * @param outcome  how it ended
     * @param duration from the handler's start to the request's completion
     */
    void request(ReadEndpoint endpoint, ReadOutcome outcome, Duration duration);

    /**
     * Records one request on a matched route that Spring MVC refused before choosing its handler (a {@code 406}
     * from content negotiation): {@code resultsstore.read.requests} only. No handler started, so there is no
     * {@code resultsstore.read.duration} to record.
     *
     * @param endpoint the endpoint
     * @param outcome  how it ended
     */
    void requestWithoutHandler(ReadEndpoint endpoint, ReadOutcome outcome);

    /**
     * Records the items of one pull or search page: {@code resultsstore.read.page.items}.
     *
     * @param items the number of items returned
     */
    void pageItems(int items);

    /**
     * Records the bytes of one payload served: {@code resultsstore.read.payload.bytes}.
     *
     * @param bytes the body's length
     */
    void payloadBytes(long bytes);
}
