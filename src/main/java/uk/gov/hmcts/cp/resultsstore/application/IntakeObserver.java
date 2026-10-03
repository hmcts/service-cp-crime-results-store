package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Duration;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;

/**
 * What intake and the extraction sweep report for metrics (contracts/metrics.md). Each event that describes a transaction is
 * reported only after that transaction commits; a failure after its rollback (FR-040).
 */
public interface IntakeObserver {

    /** A message reached the listener (every delivery, before any field of it is read). */
    void received();

    /**
     * The message had no {@code JMSMessageID}, so its receipt is keyed by its checksum (FR-005); reported
     * for the delivery, before the receipt is written.
     */
    void messageIdMissing();

    /**
     * A non-share was recorded on its receipt.
     *
     * @param reason why it is not a share
     */
    void notShare(NonShareReason reason);

    /** A redelivery found its receipt in an end state and was acknowledged with no work. */
    void alreadySettled();

    /**
     * A share was stored.
     *
     * @param outOfOrder whether a later share of the day was stored first
     * @param lag        {@code stored_at − shared_at}, never negative
     */
    void stored(boolean outOfOrder, Duration lag);

    /** A share already stored was dropped. */
    void duplicate();

    /** A stored payload's parsed copy was left empty (FR-015). */
    void parsedCopySkipped();

    /**
     * Key details could not be read, and the {@code FAILED} row that says so is committed: a share
     * stored at intake, or a row the sweep retried.
     *
     * @param stage where
     * @param kind  why
     */
    void extractionFailed(ExtractionStage stage, ExtractionFailureKind kind);

    /**
     * The extraction sweep finished one row (after its transaction, if any, ended).
     *
     * @param outcome how
     */
    void sweepRow(SweepRowOutcome outcome);

    /** An extraction sweep round threw before its rows were worked (the candidate read, or an error). */
    void sweepRoundFailed();

    /**
     * An intake attempt failed and the message goes back to the broker.
     *
     * @param stage the transaction that failed
     * @param cause why
     */
    void intakeFailed(IntakeStage stage, IntakeFailureCause cause);
}
