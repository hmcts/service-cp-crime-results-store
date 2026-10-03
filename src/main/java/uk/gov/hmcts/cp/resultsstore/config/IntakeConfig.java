package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.resultsstore.adapter.publicevents.HearingResultedEventListener;
import uk.gov.hmcts.cp.resultsstore.adapter.publicevents.RedeliveryPause;
import uk.gov.hmcts.cp.resultsstore.adapter.publicevents.Sleeper;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.application.IntakeService;
import uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractor;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser;
import uk.gov.hmcts.cp.resultsstore.application.ShareStore;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcReceiptStore;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcShareStore;

/**
 * The intake's settings, always bound and checked (FR-046), and, only while the subscription is
 * enabled, its beans: so a context with no datasource (the {@code test} profile) still starts
 * (tasks.md, wiring note).
 *
 * <p>The observer ({@link IntakeObserver}, T013) is not registered here yet; until it is, a context
 * with the subscription enabled needs it from elsewhere.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IntakeProperties.class, SweepProperties.class})
public class IntakeConfig {

    /** The PostgreSQL driver's socket timeout, in seconds; 0 or absent means none. */
    private static final String SOCKET_TIMEOUT = "spring.datasource.hikari.data-source-properties.socketTimeout";

    private static final String SUBSCRIPTION_ENABLED = "resultsstore.publicevents.enabled";

    private static final String TRUE = "true";

    /**
     * Checks the one rule that spans two settings files: a statement must time out in PostgreSQL before
     * the driver's socket does, so a long statement ends as a clean database error (research R2).
     *
     * @param intake      the intake settings
     * @param environment the environment, for the datasource's socket timeout
     */
    public IntakeConfig(final IntakeProperties intake, final Environment environment) {
        final Integer socketSeconds = environment.getProperty(SOCKET_TIMEOUT, Integer.class);
        if (socketSeconds != null && socketSeconds > 0
                && intake.store().statementTimeout().compareTo(Duration.ofSeconds(socketSeconds)) >= 0) {
            throw new IllegalStateException("resultsstore.intake.store.statement-timeout must be below "
                    + SOCKET_TIMEOUT + " (" + socketSeconds + "s)");
        }
    }

    @Bean
    @ConditionalOnProperty(name = SUBSCRIPTION_ENABLED, havingValue = TRUE)
    public ShareIdentityParser shareIdentityParser(final ObjectMapper mapper) {
        return new ShareIdentityParser(mapper);
    }

    @Bean
    @ConditionalOnProperty(name = SUBSCRIPTION_ENABLED, havingValue = TRUE)
    public KeyDetailsExtractor keyDetailsExtractor() {
        return new KeyDetailsExtractor();
    }

    @Bean
    @ConditionalOnProperty(name = SUBSCRIPTION_ENABLED, havingValue = TRUE)
    public JdbcReceiptStore jdbcReceiptStore(final JdbcClient jdbc, final PlatformTransactionManager transactions,
            final IntakeProperties intake) {
        final TransactionTemplate receiptTransaction = new TransactionTemplate(transactions);
        receiptTransaction.setTimeout(seconds(intake.receiptTimeout()));
        return new JdbcReceiptStore(jdbc, receiptTransaction);
    }

    @Bean
    @ConditionalOnProperty(name = SUBSCRIPTION_ENABLED, havingValue = TRUE)
    public JdbcShareStore jdbcShareStore(final JdbcClient jdbc, final PlatformTransactionManager transactions,
            final JdbcReceiptStore receipts, final IntakeProperties intake) {
        final TransactionTemplate storeTransaction = new TransactionTemplate(transactions);
        storeTransaction.setTimeout(seconds(intake.store().transactionTimeout()));
        return new JdbcShareStore(jdbc, storeTransaction, receipts, new JdbcShareStore.Timeouts(
                intake.store().lockTimeout(), intake.store().statementTimeout(),
                intake.store().idleInTransactionTimeout()));
    }

    @Bean
    @ConditionalOnProperty(name = SUBSCRIPTION_ENABLED, havingValue = TRUE)
    public IntakeService intakeService(final ShareIdentityParser parser, final KeyDetailsExtractor extractor,
            final JdbcReceiptStore receipts, final ShareStore shareStore, final IntakeObserver observer) {
        return new IntakeService(parser, extractor, receipts, shareStore, observer);
    }

    @Bean
    @ConditionalOnProperty(name = SUBSCRIPTION_ENABLED, havingValue = TRUE)
    public Sleeper redeliverySleeper() {
        return Thread::sleep;
    }

    @Bean
    @ConditionalOnProperty(name = SUBSCRIPTION_ENABLED, havingValue = TRUE)
    public RedeliveryPause redeliveryPause(final Sleeper sleeper, final IntakeProperties intake) {
        return new RedeliveryPause(sleeper, intake.redeliveryPause().enabled(), intake.redeliveryPause().cap());
    }

    @Bean
    @ConditionalOnProperty(name = SUBSCRIPTION_ENABLED, havingValue = TRUE)
    public HearingResultedEventListener hearingResultedEventListener(final IntakeService intake,
            final RedeliveryPause pause) {
        return new HearingResultedEventListener(intake, pause);
    }

    /** A Spring transaction timeout is whole seconds; the settings' rules keep it at 1 or more. */
    private static int seconds(final Duration timeout) {
        return Math.toIntExact(Math.max(1, timeout.toSeconds()));
    }
}
