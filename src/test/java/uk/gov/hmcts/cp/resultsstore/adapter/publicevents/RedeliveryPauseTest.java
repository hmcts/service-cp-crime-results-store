package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RedeliveryPauseTest {

    private static final Duration CAP = Duration.ofSeconds(30);

    private final List<Duration> slept = new ArrayList<>();

    @AfterEach
    void clearTheInterrupt() {
        // A test that interrupts leaves the flag on the shared test thread; clear it.
        Thread.interrupted();
    }

    @ParameterizedTest
    @CsvSource({
        "0, 1",
        "1, 2",
        "2, 4",
        "3, 8",
        "4, 16",
        "5, 30",
        "10, 30",
        "62, 30",
        "63, 30",
        "2147483647, 30",
        "-1, 1"
    })
    void delay_should_be_two_to_the_delivery_count_seconds_capped(final int deliveryCount, final long seconds) {
        assertThat(new RedeliveryPause(slept::add, true, CAP).delayFor(deliveryCount))
                .isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    void pause_should_sleep_for_the_delay() {
        final Duration paused = new RedeliveryPause(slept::add, true, CAP).pause(2);

        assertThat(paused).isEqualTo(Duration.ofSeconds(4));
        assertThat(slept).containsExactly(Duration.ofSeconds(4));
    }

    @Test
    void pause_when_disabled_should_not_sleep() {
        final Duration paused = new RedeliveryPause(slept::add, false, CAP).pause(2);

        assertThat(paused).isEqualTo(Duration.ZERO);
        assertThat(slept).isEmpty();
    }

    @Test
    void pause_interrupted_should_restore_the_interrupt_and_return() {
        final Sleeper interrupted = duration -> {
            throw new InterruptedException("stopping");
        };

        new RedeliveryPause(interrupted, true, CAP).pause(1);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    /** The one test that pauses for real (FR-045): a 1 s cap on the system clock. */
    @Test
    void pause_with_a_real_sleeper_should_wait_for_the_capped_delay() {
        final RedeliveryPause pause = new RedeliveryPause(Thread::sleep, true, Duration.ofSeconds(1));
        final long started = System.nanoTime();

        pause.pause(5);

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1))
                .isLessThan(Duration.ofSeconds(5));
    }
}
