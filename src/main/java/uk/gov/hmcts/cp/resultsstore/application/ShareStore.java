package uk.gov.hmcts.cp.resultsstore.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.ShareIdentity;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;

/**
 * The share tables, written in one store transaction per share (FR-013 to FR-017, FR-020), and the
 * extraction sweep's reads and writes (FR-033 to FR-037, research R13).
 */
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

    /**
     * The id of the share already stored with this identity, if any (specs/002-enrichment FR-006,
     * research R16): one read outside any transaction, taking no lock. It only saves progression calls;
     * the store transaction's unique key stays the real guard.
     *
     * @param identity the share's identity
     * @return the stored share's id, or empty when none is stored
     * @throws RetryableIntakeException at stage {@code STORE} when the database read fails
     */
    Optional<UUID> storedShareId(ShareIdentity identity);

    /**
     * The {@code FAILED} shares due a retry, never tried by the sweep first, then the longest since
     * tried, then oldest stored, read without locks: those read by an
     * older extractor version, and those whose reason is an unexpected error with fewer attempts than
     * the limit (FR-033, FR-035).
     *
     * @param currentVersion the extractor version now running
     * @param maxAttempts    the attempts an unexpected failure gets, the intake's included
     * @param limit          the most rows returned
     * @return the candidates, each with the attempts it had when read
     */
    List<SweepCandidate> sweepCandidates(int currentVersion, int maxAttempts, int limit);

    /**
     * A share's working copy for extraction, the one payload read the sweep makes: {@code payload_json}
     * as text, or {@code payload_text} when {@code payload_json} is NULL (text {@code jsonb} cannot hold)
     * (specs/002-enrichment FR-033). Every indexed column is derived from it, so a re-extraction indexes
     * what an enriched share's working copy holds, not only what arrived. Read outside any transaction:
     * it never changes.
     *
     * @param shareId the share
     * @return the copy to extract from
     * @throws RetryableIntakeException when the database read fails: an operational failure, which the
     *     sweep counts as the row's {@code error}, not as its failed attempt
     * @throws RuntimeException any other, e.g. no payload row for the share: the row's own failure
     */
    String payloadForExtraction(UUID shareId);

    /**
     * Records a re-extraction in one transaction under the hearing-day lock, then the share row's lock,
     * if the row is still {@code FAILED} with the attempts it had when selected (FR-034); otherwise
     * writes nothing. On success the key details and defendant rows are written, the row set
     * {@code OK} and the day's youth flags recomputed (FR-036); on failure the new reason. Either way
     * the version, attempts + 1 and the time are recorded. Returns after the commit.
     *
     * @param candidate  the row as selected
     * @param projection what the re-extraction read
     * @param version    the extractor version that read it
     * @return {@code FIXED}, {@code FAILED_AGAIN} or {@code SKIPPED}
     * @throws RuntimeException when the transaction fails; nothing is written, and the sweep counts it
     *     as an operational {@code error}, not as the row's failed attempt
     */
    SweepRowOutcome recordReextraction(SweepCandidate candidate, Projection projection, int version);

    /**
     * Records that the sweep tried a row, {@code projection_tried_at = now()}, in its own short transaction,
     * after the attempt and whatever it wrote. Nothing else changes. The candidates are taken never
     * tried first, then the longest since tried, so a row that keeps failing rotates behind the others.
     *
     * @param shareId the share
     * @throws RuntimeException when the transaction fails; nothing is written
     */
    void recordSweepAttempt(UUID shareId);
}
