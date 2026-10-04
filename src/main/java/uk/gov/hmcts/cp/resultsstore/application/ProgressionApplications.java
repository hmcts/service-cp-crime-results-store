package uk.gov.hmcts.cp.resultsstore.application;

import java.util.UUID;

/**
 * Progression's application-only query, asked once per court application that arrives without
 * results (specs/002-enrichment/contracts/progression-lookup.md). The adapter makes one request and
 * never retries.
 */
public interface ProgressionApplications {

    /**
     * Asks progression for one application.
     *
     * @param applicationId the application's id, already accepted as a canonical UUID
     * @return what progression answered: the application, or that it has none by that id
     * @throws RetryableIntakeException at stage {@code ENRICH} with a {@code progression_*} cause, and no
     *                                  chained cause, for every answer the contract fails closed on
     */
    ApplicationAnswer find(UUID applicationId);
}
