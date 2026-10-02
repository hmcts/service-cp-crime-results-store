package uk.gov.hmcts.cp.resultsstore.application;

/** The share tables, written in one store transaction per share (FR-013 to FR-017, FR-020). */
public interface ShareStore {

    /**
     * Stores a share in one transaction under its hearing-day lock and marks its receipt, or, when a
     * share with the same identity is already stored, marks the receipt {@code DUPLICATE} with that
     * share's id. Returns after the commit.
     *
     * @param request the share
     * @return what was done
     * @throws RetryableIntakeException when the transaction fails; nothing is left behind
     */
    StoreResult store(StoreRequest request);
}
