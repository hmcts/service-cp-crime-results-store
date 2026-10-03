package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;
import java.time.Instant;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ErrorHandler;
import uk.gov.hmcts.cp.resultsstore.application.ExtractionSweep;

/**
 * Runs the extraction sweep's rounds on a scheduler of its own, one thread, a fixed delay apart
 * (FR-037). The scheduler is held here rather than registered as a bean, so no {@code Executor} bean
 * appears and Boot's own task executor is created as before. A round that throws goes to the error
 * handler and the next round still runs.
 *
 * <p>Start and stop are idempotent. Stop interrupts a round in progress, which then ends after the
 * row it is on (a JDBC call may not notice the interrupt, so that row's transaction runs to its end,
 * bounded by the store transaction's timeout), and returns once the thread has ended or that bound has
 * passed, so the datasource is not closed under a running row.
 */
public class SweepSchedule implements SmartLifecycle {

    private final ExtractionSweep sweep;

    private final Duration initialDelay;

    private final Duration fixedDelay;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    private volatile boolean running;

    /**
     * Creates the schedule; it starts with the context.
     *
     * @param sweep        the sweep
     * @param initialDelay the wait before the first round
     * @param fixedDelay   the wait between the end of one round and the start of the next
     * @param stopBound    how long stop waits for a round in progress to end
     * @param errorHandler told of a round that throws
     */
    public SweepSchedule(final ExtractionSweep sweep, final Duration initialDelay, final Duration fixedDelay,
            final Duration stopBound, final ErrorHandler errorHandler) {
        this.sweep = sweep;
        this.initialDelay = initialDelay;
        this.fixedDelay = fixedDelay;
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("extraction-sweep-");
        scheduler.setErrorHandler(errorHandler);
        // Interrupt on stop (shutdownNow), then wait for the round's current row to end.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationMillis(stopBound.toMillis());
    }

    @Override
    public synchronized void start() {
        if (!running) {
            scheduler.initialize();
            scheduler.scheduleWithFixedDelay(sweep::runRound, Instant.now().plus(initialDelay), fixedDelay);
            running = true;
        }
    }

    @Override
    public synchronized void stop() {
        if (running) {
            // Interrupts a round in progress, which stops before its next row, and waits for it to end.
            scheduler.shutdown();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
