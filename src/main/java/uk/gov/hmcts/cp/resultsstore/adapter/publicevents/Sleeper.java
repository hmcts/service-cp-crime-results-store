package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import java.time.Duration;

/** Waits, so the redelivery pause can be faked in tests (research R14). */
@FunctionalInterface
public interface Sleeper {

    /**
     * Waits for the duration.
     *
     * @param duration how long
     * @throws InterruptedException when the thread is interrupted while waiting
     */
    void sleep(Duration duration) throws InterruptedException;
}
