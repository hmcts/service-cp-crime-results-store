package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** What one store transaction did. */
public sealed interface StoreResult permits StoreResult.Stored, StoreResult.Duplicate {

    /**
     * The share was stored and its receipt marked {@code STORED}.
     *
     * @param shareId           the share id
     * @param storedAt          {@code stored_at}
     * @param outOfOrder        whether a later share of the day was stored first
     * @param enrichmentApplied the {@code enrichment_applied} actually stored (specs/002-enrichment FR-030)
     * @param insertToCommit    from sending the share insert to the commit returning, on a monotonic clock: at
     *                          least the time the share's number was held open (specs/003-read-api FR-020)
     */
    record Stored(UUID shareId, Instant storedAt, boolean outOfOrder, boolean enrichmentApplied,
            Duration insertToCommit) implements StoreResult {

        /**
         * The same result with its measured time.
         *
         * @param measured from sending the share insert to the commit returning
         * @return the result
         */
        public Stored withInsertToCommit(final Duration measured) {
            return new Stored(shareId, storedAt, outOfOrder, enrichmentApplied, measured);
        }
    }

    /**
     * A share with the same identity was already stored; the receipt is marked {@code DUPLICATE}.
     *
     * @param existingShareId the stored share's id (FR-012)
     */
    record Duplicate(UUID existingShareId) implements StoreResult {
    }
}
