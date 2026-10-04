package uk.gov.hmcts.cp.resultsstore.persistence;

import java.sql.SQLException;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;

/**
 * Classifies a failed intake transaction by the SQLSTATE it carries (contracts/metrics.md):
 * {@code 55P03} lock timeout, {@code 57014} statement timeout, anything else database.
 */
public final class RetryableFailures {

    /** How deep the cause chain is searched; a chain is never this long unless it loops. */
    private static final int MAX_DEPTH = 32;

    private RetryableFailures() {
        // Static functions only.
    }

    /**
     * Classifies a database failure: a {@code DataAccessException} or a {@code TransactionException}.
     *
     * @param stage   the transaction that failed
     * @param failure the failure
     * @return the exception to throw, with the failure as its cause
     */
    public static RetryableIntakeException classify(final IntakeStage stage, final RuntimeException failure) {
        final String sqlState = sqlState(failure);
        final IntakeFailureCause cause =
                sqlState == null ? IntakeFailureCause.DATABASE : IntakeFailureCause.fromSqlState(sqlState);
        return new RetryableIntakeException(stage, cause, failure);
    }

    /**
     * The SQLSTATE of the first {@link SQLException} in the cause chain that has one.
     *
     * @param failure the failure
     * @return the SQLSTATE, or {@code null} when the chain carries none
     */
    /* default */ static String sqlState(final Throwable failure) {
        String sqlState = null;
        Throwable current = failure;
        for (int depth = 0; sqlState == null && current != null && depth < MAX_DEPTH; depth++) {
            if (current instanceof SQLException sql) {
                sqlState = sql.getSQLState();
            }
            current = current.getCause();
        }
        return sqlState;
    }
}
