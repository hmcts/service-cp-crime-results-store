package uk.gov.hmcts.cp.resultsstore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ErrorHandler;
import uk.gov.hmcts.cp.resultsstore.application.ExtractionSweep;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractor;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser;
import uk.gov.hmcts.cp.resultsstore.application.ShareStore;

/**
 * The extraction sweep (FR-033 to FR-037, research R13), created only while the subscription and the
 * sweep are both enabled: it works on the intake's store, parser, extractor and observer, which exist
 * only with the subscription (tasks.md, wiring note). Its settings are {@link SweepProperties}, bound
 * and checked at start by {@link IntakeConfig}. No distributed lock: each pod sweeps on its own
 * thread, and the store's per-row re-check under the locks keeps that correct.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "resultsstore", name = {"publicevents.enabled", "sweep.enabled"},
        havingValue = "true")
public class SweepSchedulingConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SweepSchedulingConfig.class);

    @Bean
    public ExtractionSweep extractionSweep(final ShareStore store, final ShareIdentityParser parser,
            final KeyDetailsExtractor extractor, final IntakeObserver observer, final SweepProperties sweep) {
        return new ExtractionSweep(store, parser, extractor, observer, new ExtractionSweep.Settings(
                KeyDetailsExtractor.EXTRACTOR_VERSION, sweep.maxAttempts(), sweep.batchSize()));
    }

    @Bean
    public SweepSchedule sweepSchedule(final ExtractionSweep extractionSweep, final IntakeObserver observer,
            final SweepProperties sweep, final IntakeProperties intake) {
        return new SweepSchedule(extractionSweep, sweep.initialDelay(), sweep.fixedDelay(),
                intake.store().transactionTimeout(), roundFailureHandler(observer));
    }

    /**
     * What the schedule is told of a round that throws.
     *
     * @param observer the metrics port
     * @return the handler
     */
    /* default */ static ErrorHandler roundFailureHandler(final IntakeObserver observer) {
        return failure -> roundFailed(observer, failure);
    }

    /**
     * A round that threw before its rows (the candidate read), or an {@link Error} from anywhere in it:
     * counted on {@code resultsstore.sweep.rounds.failed} and logged by class alone, as the intake's
     * error handler does. A runtime failure is then done with, and the next round runs at its time; an
     * {@code Error} is thrown on, which ends the schedule's recurrence rather than carrying on in a
     * state the JVM cannot vouch for.
     */
    private static void roundFailed(final IntakeObserver observer, final Throwable failure) {
        observer.sweepRoundFailed();
        LOG.error("Extraction sweep round failed. causes={}", PublicEventsConfig.causeClasses(failure));
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
