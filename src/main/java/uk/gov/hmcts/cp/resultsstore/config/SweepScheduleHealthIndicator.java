package uk.gov.hmcts.cp.resultsstore.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The extraction sweep's schedule as the {@code sweepSchedule} health contributor, in the liveness
 * group ({@code management.endpoint.health.group.liveness.include}): {@code DOWN} once the schedule
 * has ended on an {@link Error}, so Kubernetes restarts the pod; readiness is not affected. The state
 * lives in {@link SweepSchedule#status()}. Registered whether or not the sweep runs, so the group
 * always names a contributor that exists; with no schedule it is {@code UP}.
 */
@Component
public class SweepScheduleHealthIndicator implements HealthIndicator {

    private final ObjectProvider<SweepSchedule> schedule;

    /**
     * Creates the indicator.
     *
     * @param schedule the sweep's schedule, absent when the subscription or the sweep is switched off
     */
    public SweepScheduleHealthIndicator(final ObjectProvider<SweepSchedule> schedule) {
        this.schedule = schedule;
    }

    @Override
    public Health health() {
        return Health.status(schedule.stream().map(SweepSchedule::status).findFirst().orElse(Status.UP)).build();
    }
}
