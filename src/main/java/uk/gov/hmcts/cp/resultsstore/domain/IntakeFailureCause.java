package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/** Why an intake attempt failed: the {@code cause} tag of {@code resultsstore.intake.failed}. */
public enum IntakeFailureCause {

    /** SQLSTATE {@code 55P03}: the lock wait passed {@code lock_timeout}. */
    LOCK_TIMEOUT,
    /** SQLSTATE {@code 57014}: a statement passed its timeout, or was cancelled. */
    STATEMENT_TIMEOUT,
    /** Any other database failure. */
    DATABASE,
    /** Not a database failure. */
    OTHER;

    /** PostgreSQL's {@code lock_not_available}, raised when {@code lock_timeout} passes. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /** PostgreSQL's {@code query_canceled}, raised when {@code statement_timeout} passes or a JDBC timeout cancels. */
    private static final String QUERY_CANCELED = "57014";

    /**
     * The cause a SQLSTATE names.
     *
     * @param sqlState the SQLSTATE, or {@code null} when the failure carried none
     * @return the cause
     */
    public static IntakeFailureCause fromSqlState(final String sqlState) {
        final IntakeFailureCause cause;
        if (sqlState == null) {
            cause = OTHER;
        } else if (LOCK_NOT_AVAILABLE.equals(sqlState)) {
            cause = LOCK_TIMEOUT;
        } else if (QUERY_CANCELED.equals(sqlState)) {
            cause = STATEMENT_TIMEOUT;
        } else {
            cause = DATABASE;
        }
        return cause;
    }

    /** The {@code cause} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
