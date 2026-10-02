package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Duration;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;

/**
 * What intake reports for metrics (contracts/metrics.md). Each event that describes a transaction is
 * reported only after that transaction commits; a failure after its rollback (FR-040).
 */
public interface IntakeObserver {

    /** A message reached intake (every delivery, before any work). */
    void received();

    /** The message had no {@code JMSMessageID} and was keyed by its checksum (FR-005). */
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
     * A share was stored with its key details unread ({@code FAILED}).
     *
     * @param kind why
     */
    void extractionFailed(ExtractionFailureKind kind);

    /**
     * An intake attempt failed and the message goes back to the broker.
     *
     * @param stage the transaction that failed
     * @param cause why
     */
    void intakeFailed(IntakeStage stage, IntakeFailureCause cause);
}
