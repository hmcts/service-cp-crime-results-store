package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Status;
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
 * <p>When the handler throws on (an {@link Error}), the fixed-delay task ends for good: the schedule
 * keeps the task's future, logs the end once, and is from then on neither running nor healthy
 * ({@link #status()}, reported in the liveness group by {@link SweepScheduleHealthIndicator}), so
 * Kubernetes restarts the pod. Its threads are daemons, so a schedule ended this way, which the
 * context then no longer stops, never holds the JVM open.
 *
 * <p>Start and stop are idempotent. Stop first asks the sweep to stop ({@link ExtractionSweep#stop()}:
 * no further transaction is opened, neither a row's write nor the record of its try), then interrupts
 * a round in progress, which ends after the row it is on (a JDBC call may not notice the interrupt, so
 * a transaction already open runs to its end, bounded by the store transaction's timeout), and returns
 * once the thread has ended or the stop bound (two store transaction timeouts: the write and the try)
 * has passed, so the datasource is not closed under a running row. A stop that reaches its bound is
 * logged.
 */
public class SweepSchedule implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(SweepSchedule.class);

    private final ExtractionSweep sweep;

    private final Duration initialDelay;

    private final Duration fixedDelay;

    private final Duration stopBound;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    private volatile boolean running;

    /** The fixed-delay task; set by the first start, before {@link #running}. */
    private volatile ScheduledFuture<?> rounds;

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
        scheduler.setDaemon(true);
        scheduler.setErrorHandler(failure -> handled(errorHandler, failure));
        // Interrupt on stop (shutdownNow), then wait for the round's current row to end.
        this.stopBound = stopBound;
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationMillis(stopBound.toMillis());
    }

    @Override
    public synchronized void start() {
        if (!running) {
            sweep.start();
            scheduler.initialize();
            rounds = scheduler.scheduleWithFixedDelay(sweep::runRound, Instant.now().plus(initialDelay), fixedDelay);
            running = true;
        }
    }

    @Override
    public synchronized void stop() {
        if (running) {
            sweep.stop();
            // Interrupts a round in progress, which stops before its next row, and waits for it to end.
            scheduler.shutdown();
            running = false;
            if (!scheduler.getScheduledExecutor().isTerminated()) {
                LOG.warn("Extraction sweep stop timed out after {} ms; its current row may still be running.",
                        stopBound.toMillis());
            }
        }
    }

    /** Started, not stopped, and the fixed-delay task has not ended on an {@link Error}. */
    @Override
    public boolean isRunning() {
        return running && !rounds.isDone();
    }

    /**
     * The schedule's health: {@code DOWN} once its task has ended while it was meant to be running,
     * {@code UP} otherwise (before start and after stop included).
     *
     * @return the status
     */
    public Status status() {
        return running && rounds.isDone() ? Status.DOWN : Status.UP;
    }

    /**
     * Passes a round's failure to the handler; when the handler throws on, the task ends with it, and
     * that end is logged once, by class names alone.
     */
    private static void handled(final ErrorHandler handler, final Throwable failure) {
        boolean handled = false;
        try {
            handler.handleError(failure);
            handled = true;
        } finally {
            if (!handled) {
                LOG.error("Extraction sweep schedule ended; no further rounds run until the pod restarts. causes={}",
                        PublicEventsConfig.causeClasses(failure));
            }
        }
    }
}
