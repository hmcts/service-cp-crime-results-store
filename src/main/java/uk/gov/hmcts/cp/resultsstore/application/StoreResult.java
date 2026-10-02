package uk.gov.hmcts.cp.resultsstore.application;

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
     * @param parsedCopySkipped whether the payload's parsed copy was left empty (FR-015)
     */
    record Stored(UUID shareId, Instant storedAt, boolean outOfOrder, boolean parsedCopySkipped)
            implements StoreResult {
    }

    /**
     * A share with the same identity was already stored; the receipt is marked {@code DUPLICATE}.
     *
     * @param existingShareId the stored share's id (FR-012)
     */
    record Duplicate(UUID existingShareId) implements StoreResult {
    }
}
