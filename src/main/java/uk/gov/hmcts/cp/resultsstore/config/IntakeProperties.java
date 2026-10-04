package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code resultsstore.intake.*} (contracts/configuration.md).
 *
 * @param redeliveryPause the pause before a failed message goes back to the broker
 * @param receiptTimeout  the receipt transaction's timeout
 * @param store           the store transaction's timeouts
 */
@ConfigurationProperties("resultsstore.intake")
public record IntakeProperties(@DefaultValue Pause redeliveryPause, @DefaultValue("10s") Duration receiptTimeout,
        @DefaultValue Store store) {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private static final String TRANSACTION_TIMEOUT = "resultsstore.intake.store.transaction-timeout";

    private static final String STATEMENT_TIMEOUT = "resultsstore.intake.store.statement-timeout";

    private static final String LOCK_TIMEOUT = "resultsstore.intake.store.lock-timeout";

    private static final String IDLE_TIMEOUT = "resultsstore.intake.store.idle-in-transaction-timeout";

    /** Checks the rules of contracts/configuration.md; a bad value stops the service starting. */
    public IntakeProperties {
        Rules.within("resultsstore.intake.receipt-timeout", receiptTimeout, ONE_SECOND, ONE_MINUTE);
    }

    /**
     * {@code resultsstore.intake.redelivery-pause.*}.
     *
     * @param enabled whether the listener pauses before a rollback
     * @param cap     the longest pause
     */
    public record Pause(@DefaultValue("true") boolean enabled, @DefaultValue("30s") Duration cap) {

        /** Checks the cap: 1 s to 5 min. */
        public Pause {
            Rules.within("resultsstore.intake.redelivery-pause.cap", cap, ONE_SECOND, FIVE_MINUTES);
        }
    }

    /**
     * {@code resultsstore.intake.store.*}: applied per store transaction (research R2).
     *
     * @param transactionTimeout        the Spring transaction timeout, the outer bound
     * @param lockTimeout               PostgreSQL {@code lock_timeout}
     * @param statementTimeout          PostgreSQL {@code statement_timeout}
     * @param idleInTransactionTimeout  PostgreSQL {@code idle_in_transaction_session_timeout}
     */
    public record Store(@DefaultValue("60s") Duration transactionTimeout, @DefaultValue("5s") Duration lockTimeout,
            @DefaultValue("10s") Duration statementTimeout, @DefaultValue("10s") Duration idleInTransactionTimeout) {

        /**
         * Checks each timeout and how they relate. The transaction timeout must be whole seconds and the
         * PostgreSQL limits whole milliseconds, the units they are enforced in, so that
         * {@link #visibilityBound()} is the bound actually enforced.
         */
        public Store {
            Rules.whole(TRANSACTION_TIMEOUT, transactionTimeout, ChronoUnit.SECONDS);
            Rules.whole(STATEMENT_TIMEOUT, statementTimeout, ChronoUnit.MILLIS);
            Rules.whole(LOCK_TIMEOUT, lockTimeout, ChronoUnit.MILLIS);
            Rules.whole(IDLE_TIMEOUT, idleInTransactionTimeout, ChronoUnit.MILLIS);
            Rules.positive(LOCK_TIMEOUT, lockTimeout);
            Rules.positive(STATEMENT_TIMEOUT, statementTimeout);
            Rules.positive(IDLE_TIMEOUT, idleInTransactionTimeout);
            Rules.atMost(LOCK_TIMEOUT, lockTimeout, STATEMENT_TIMEOUT, statementTimeout);
            Rules.atMost(LOCK_TIMEOUT, lockTimeout, TRANSACTION_TIMEOUT, transactionTimeout);
            Rules.atMost(STATEMENT_TIMEOUT, statementTimeout, TRANSACTION_TIMEOUT, transactionTimeout);
            Rules.atMost(IDLE_TIMEOUT, idleInTransactionTimeout, TRANSACTION_TIMEOUT, transactionTimeout);
        }

        /**
         * The longest a store transaction can hold a share's number open, every limit enforced by PostgreSQL or by
         * Spring before a statement (specs/003-read-api research R4): transaction + 2 × statement +
         * idle-in-transaction, 90 s at the defaults. The pull's visibility lag must be at least this.
         *
         * @return the bound
         */
        public Duration visibilityBound() {
            return transactionTimeout.plus(statementTimeout.multipliedBy(2)).plus(idleInTransactionTimeout);
        }
    }
}
