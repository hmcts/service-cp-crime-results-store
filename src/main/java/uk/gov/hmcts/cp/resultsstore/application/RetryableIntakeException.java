package uk.gov.hmcts.cp.resultsstore.application;

import java.io.Serial;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;

/**
 * An intake transaction failed and rolled back; the broker should redeliver the message. Thrown by
 * the persistence adapters, which classify the failure; its message holds the stage and cause only.
 */
public class RetryableIntakeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final IntakeStage stage;

    private final IntakeFailureCause failureCause;

    /**
     * Creates the exception.
     *
     * @param stage        the transaction that failed
     * @param failureCause why
     * @param cause        the failure
     */
    public RetryableIntakeException(final IntakeStage stage, final IntakeFailureCause failureCause,
            final Throwable cause) {
        super("intake failed at " + stage + ": " + failureCause, cause);
        this.stage = stage;
        this.failureCause = failureCause;
    }

    /** The transaction that failed. */
    public IntakeStage getStage() {
        return stage;
    }

    /** Why it failed. */
    public IntakeFailureCause getFailureCause() {
        return failureCause;
    }
}
