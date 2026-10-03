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
     * @param errorHandler told of a round that throws
     */
    public SweepSchedule(final ExtractionSweep sweep, final Duration initialDelay, final Duration fixedDelay,
            final ErrorHandler errorHandler) {
        this.sweep = sweep;
        this.initialDelay = initialDelay;
        this.fixedDelay = fixedDelay;
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("extraction-sweep-");
        scheduler.setErrorHandler(errorHandler);
    }

    @Override
    public synchronized void start() {
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(sweep::runRound, Instant.now().plus(initialDelay), fixedDelay);
        running = true;
    }

    @Override
    public synchronized void stop() {
        // Interrupts a round in progress: its open row transaction rolls back and the row stays FAILED.
        scheduler.shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
