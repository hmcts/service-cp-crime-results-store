package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import java.time.Duration;

/**
 * The capped pause before a failed message goes back to the broker: {@code min(2^deliveryCount s, cap)}
 * (FR-045, research R14). The broker redelivers at once, so without it a short database outage would
 * use up every redelivery in seconds. The base is fixed (D2); only the cap is a setting.
 */
public class RedeliveryPause {

    /** Past this, {@code 2^n} seconds no longer fits a {@code long}; every cap is far below it. */
    private static final int LARGEST_EXPONENT = 62;

    private final Sleeper sleeper;

    private final boolean enabled;

    private final Duration cap;

    /**
     * Creates the pause.
     *
     * @param sleeper waits
     * @param enabled whether to pause at all
     * @param cap     the longest pause
     */
    public RedeliveryPause(final Sleeper sleeper, final boolean enabled, final Duration cap) {
        this.sleeper = sleeper;
        this.enabled = enabled;
        this.cap = cap;
    }

    /**
     * The pause for a delivery.
     *
     * @param deliveryCount the broker's delivery count; below 0 counts as 0
     * @return {@code min(2^deliveryCount s, cap)}
     */
    public Duration delayFor(final int deliveryCount) {
        final Duration uncapped = Duration.ofSeconds(1L << Math.clamp(deliveryCount, 0, LARGEST_EXPONENT));
        return uncapped.compareTo(cap) > 0 ? cap : uncapped;
    }

    /**
     * Pauses for the delivery's delay, unless switched off. If the thread is interrupted while it
     * waits, the interrupt is restored and the pause ends there; the caller rethrows its failure either
     * way, so nothing is lost.
     *
     * @param deliveryCount the broker's delivery count
     * @return the pause asked for; {@link Duration#ZERO} when switched off
     */
    public Duration pause(final int deliveryCount) {
        final Duration delay = enabled ? delayFor(deliveryCount) : Duration.ZERO;
        if (enabled) {
            try {
                sleeper.sleep(delay);
            } catch (final InterruptedException interrupted) {
                // Recorded outcome: the flag is restored for the container, and the caller rethrows.
                Thread.currentThread().interrupt();
            }
        }
        return delay;
    }
}
