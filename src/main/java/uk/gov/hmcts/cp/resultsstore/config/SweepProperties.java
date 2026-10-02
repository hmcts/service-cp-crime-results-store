package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code resultsstore.sweep.*} (contracts/configuration.md). The sweep itself arrives with T012.
 *
 * @param enabled      whether the scheduled sweep runs
 * @param initialDelay the wait before the first round
 * @param fixedDelay   the wait between rounds
 * @param batchSize    the rows one round takes
 * @param maxAttempts  the extraction attempts for an {@code UNEXPECTED} failure, the intake's included
 */
@ConfigurationProperties("resultsstore.sweep")
public record SweepProperties(@DefaultValue("true") boolean enabled, @DefaultValue("1m") Duration initialDelay,
        @DefaultValue("5m") Duration fixedDelay, @DefaultValue("100") int batchSize,
        @DefaultValue("3") int maxAttempts) {

    private static final int LARGEST_BATCH = 1000;

    private static final int MOST_ATTEMPTS = 10;

    /** Checks the rules of contracts/configuration.md; a bad value stops the service starting. */
    public SweepProperties {
        Rules.atLeast("resultsstore.sweep.initial-delay", initialDelay, Duration.ZERO);
        Rules.within("resultsstore.sweep.fixed-delay", fixedDelay, Duration.ofSeconds(10), Duration.ofHours(24));
        Rules.within("resultsstore.sweep.batch-size", batchSize, 1, LARGEST_BATCH);
        Rules.within("resultsstore.sweep.max-attempts", maxAttempts, 1, MOST_ATTEMPTS);
    }
}
