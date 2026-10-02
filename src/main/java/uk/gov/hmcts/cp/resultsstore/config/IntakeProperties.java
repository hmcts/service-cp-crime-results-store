package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;
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
    public record Store(@DefaultValue("60s") Duration transactionTimeout, @DefaultValue("10s") Duration lockTimeout,
            @DefaultValue("20s") Duration statementTimeout, @DefaultValue("10s") Duration idleInTransactionTimeout) {

        /** Checks each timeout and how they relate. */
        public Store {
            Rules.positive("resultsstore.intake.store.lock-timeout", lockTimeout);
            Rules.positive("resultsstore.intake.store.statement-timeout", statementTimeout);
            Rules.positive("resultsstore.intake.store.idle-in-transaction-timeout", idleInTransactionTimeout);
            Rules.atMost("resultsstore.intake.store.lock-timeout", lockTimeout,
                    "resultsstore.intake.store.statement-timeout", statementTimeout);
            Rules.atMost("resultsstore.intake.store.lock-timeout", lockTimeout,
                    "resultsstore.intake.store.transaction-timeout", transactionTimeout);
            Rules.atMost("resultsstore.intake.store.statement-timeout", statementTimeout,
                    "resultsstore.intake.store.transaction-timeout", transactionTimeout);
            Rules.atMost("resultsstore.intake.store.idle-in-transaction-timeout", idleInTransactionTimeout,
                    "resultsstore.intake.store.transaction-timeout", transactionTimeout);
        }
    }
}
