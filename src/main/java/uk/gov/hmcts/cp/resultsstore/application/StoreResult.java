package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Instant;
import java.util.UUID;

/** What one store transaction did. */
public sealed interface StoreResult permits StoreResult.Stored, StoreResult.Duplicate,
        StoreResult.EnrichedCopyRefused {

    /**
     * The share was stored and its receipt marked {@code STORED}.
     *
     * @param shareId           the share id
     * @param storedAt          {@code stored_at}
     * @param outOfOrder        whether a later share of the day was stored first
     * @param parsedCopySkipped whether the payload's parsed copy was left empty (FR-015)
     * @param enrichmentApplied the {@code enrichment_applied} actually stored (specs/002-enrichment FR-030)
     */
    record Stored(UUID shareId, Instant storedAt, boolean outOfOrder, boolean parsedCopySkipped,
            boolean enrichmentApplied) implements StoreResult {
    }

    /**
     * A share with the same identity was already stored; the receipt is marked {@code DUPLICATE}.
     *
     * @param existingShareId the stored share's id (FR-012)
     */
    record Duplicate(UUID existingShareId) implements StoreResult {
    }

    /**
     * The enriched copy cannot be held in {@code payload_json}: nothing was written, the receipt is
     * still {@code RECEIVED}, and the caller stores the arrived copy instead (specs/002-enrichment
     * FR-019, research R18).
     */
    record EnrichedCopyRefused() implements StoreResult {
    }
}
