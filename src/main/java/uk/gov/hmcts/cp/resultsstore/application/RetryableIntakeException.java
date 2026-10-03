package uk.gov.hmcts.cp.resultsstore.application;

import java.io.Serial;
import java.util.Optional;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;

/**
 * An intake attempt failed; the broker should redeliver the message. Thrown by the persistence
 * adapters, which classify a failed transaction and chain the database failure, and by the progression
 * adapter, which classifies a failed lookup and chains nothing: a Jackson message or a response body
 * must not reach a stack trace. Its message holds the stage, the cause and, from the progression
 * adapter, what failed; never exception text.
 */
public class RetryableIntakeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final IntakeStage stage;

    private final IntakeFailureCause failureCause;

    private final String failedClassName;

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
        this.failedClassName = null;
    }

    /**
     * Creates the exception with no chained cause (research R14).
     *
     * @param stage           the step that failed
     * @param failureCause    why
     * @param failedClassName what failed: the failed exception's simple class name, or, where no
     *                        exception was thrown, a bounded label (an HTTP status as {@code status 404},
     *                        a JSON node type); never text from the failure or the body
     */
    public RetryableIntakeException(final IntakeStage stage, final IntakeFailureCause failureCause,
            final String failedClassName) {
        super("intake failed at " + stage + ": " + failureCause + " (" + failedClassName + ")", null);
        this.stage = stage;
        this.failureCause = failureCause;
        this.failedClassName = failedClassName;
    }

    /** The transaction that failed. */
    public IntakeStage getStage() {
        return stage;
    }

    /** Why it failed. */
    public IntakeFailureCause getFailureCause() {
        return failureCause;
    }

    /** What failed, when the exception chains no cause. */
    public Optional<String> getFailedClassName() {
        return Optional.ofNullable(failedClassName);
    }
}
