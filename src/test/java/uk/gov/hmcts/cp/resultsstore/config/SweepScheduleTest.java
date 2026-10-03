package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.resultsstore.application.ExtractionSweep;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;

/**
 * The sweep's schedule as a lifecycle (FR-037): one round at a time on its own thread, a second start
 * changes nothing, a failed round does not end the schedule, and stop interrupts a round in progress
 * and returns only once the round has ended.
 */
@DisplayName("sweep schedule")
class SweepScheduleTest {

    private static final Duration SHORT = Duration.ofMillis(20);

    private static final Duration STOP_BOUND = Duration.ofSeconds(5);

    private static final Duration WITHIN = Duration.ofSeconds(5);

    private final ExtractionSweep sweep = mock(ExtractionSweep.class);

    private final AtomicInteger rounds = new AtomicInteger();

    private final List<Throwable> handled = new CopyOnWriteArrayList<>();

    private SweepSchedule schedule;

    @AfterEach
    void stopSchedule() {
        if (schedule != null) {
            schedule.stop();
        }
    }

    private SweepSchedule schedule() {
        schedule = new SweepSchedule(sweep, Duration.ZERO, SHORT, STOP_BOUND, handled::add);
        return schedule;
    }

    @Test
    void schedule_should_be_running_only_between_start_and_stop() {
        when(sweep.runRound()).thenReturn(List.of());
        final SweepSchedule underTest = schedule();
        assertThat(underTest.isRunning()).isFalse();

        underTest.start();
        assertThat(underTest.isRunning()).isTrue();
        underTest.stop();

        assertThat(underTest.isRunning()).isFalse();
    }

    @Test
    void stop_before_start_should_do_nothing() {
        final SweepSchedule underTest = schedule();

        underTest.stop();

        assertThat(underTest.isRunning()).isFalse();
    }

    @Test
    void second_start_should_not_leave_rounds_running_after_stop() throws InterruptedException {
        when(sweep.runRound()).thenAnswer(invocation -> {
            rounds.incrementAndGet();
            return List.of();
        });
        final SweepSchedule underTest = schedule();

        underTest.start();
        underTest.start();
        await().atMost(WITHIN).until(() -> rounds.get() >= 3);
        underTest.stop();
        final int atStop = rounds.get();
        TimeUnit.MILLISECONDS.sleep(SHORT.toMillis() * 10);

        assertThat(rounds.get()).isEqualTo(atStop);
    }

    @Test
    void failed_round_should_go_to_the_handler_and_the_next_round_should_still_run() {
        when(sweep.runRound()).thenAnswer(invocation -> {
            if (rounds.incrementAndGet() == 1) {
                throw new IllegalStateException("first round");
            }
            return List.of(SweepRowOutcome.FIXED);
        });

        schedule().start();

        await().atMost(WITHIN).until(() -> rounds.get() >= 2);
        assertThat(handled).singleElement().isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stop_should_interrupt_a_round_in_progress_and_return_only_once_it_has_ended() throws InterruptedException {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean ended = new AtomicBoolean();
        when(sweep.runRound()).thenAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (final InterruptedException interrupted) {
                // A driver that ignores the interrupt for a while: the row's statement runs on.
                busyFor(Duration.ofMillis(300));
                Thread.currentThread().interrupt();
            }
            ended.set(true);
            return List.of();
        });
        final SweepSchedule underTest = schedule();
        underTest.start();
        assertThat(started.await(WITHIN.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        underTest.stop();

        assertThat(ended).isTrue();
        assertThat(underTest.isRunning()).isFalse();
    }

    /** Waits without observing interrupts, as a blocked socket read does. */
    private static void busyFor(final Duration duration) {
        final long until = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }
}
