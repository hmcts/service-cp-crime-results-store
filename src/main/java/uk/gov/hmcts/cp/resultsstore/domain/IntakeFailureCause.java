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
    OTHER,
    /** Progression answered 404, a 3xx, a 2xx other than 200, or any status not listed elsewhere. */
    PROGRESSION_REJECTED,
    /** Progression answered 401 or 403: the system user is missing, unknown or not admitted. */
    PROGRESSION_REFUSED,
    /** Progression answered 408, 429 or a 5xx. */
    PROGRESSION_UNAVAILABLE,
    /** Progression could not be reached: refused, unknown host, or reset before the status line. */
    PROGRESSION_UNREACHABLE,
    /** A connect or read timeout, or the whole-response deadline passed. */
    PROGRESSION_TIMEOUT,
    /** Progression's 200 body broke the contract: not JSON, cut short, or of the wrong shape. */
    PROGRESSION_MALFORMED;

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

    /**
     * Whether {@code resultsstore.intake.failed} pairs this cause with the stage (contracts/metrics.md):
     * the database causes with {@code receipt} and {@code store}, the {@code progression_*} causes with
     * {@code enrich} only, and {@code other} with every stage.
     *
     * @param stage the stage
     * @return whether the pair is one the contract lists
     */
    public boolean belongsTo(final IntakeStage stage) {
        return switch (this) {
            case OTHER -> true;
            case LOCK_TIMEOUT, STATEMENT_TIMEOUT, DATABASE -> stage != IntakeStage.ENRICH;
            case PROGRESSION_REJECTED, PROGRESSION_REFUSED, PROGRESSION_UNAVAILABLE, PROGRESSION_UNREACHABLE,
                    PROGRESSION_TIMEOUT, PROGRESSION_MALFORMED -> stage == IntakeStage.ENRICH;
        };
    }

    /** The {@code cause} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
