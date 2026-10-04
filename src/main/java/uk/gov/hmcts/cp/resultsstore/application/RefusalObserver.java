package uk.gov.hmcts.cp.resultsstore.application;

import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;

/**
 * Counts the requests refused before they reach the audit filter ({@code resultsstore.read.refused},
 * contracts/metrics.md). Those requests are not audited, so this count is their only record in the service.
 */
public interface RefusalObserver {

    /**
     * Records one refused request.
     *
     * @param reason why it was refused
     */
    void refused(RouteRefusal reason);
}
