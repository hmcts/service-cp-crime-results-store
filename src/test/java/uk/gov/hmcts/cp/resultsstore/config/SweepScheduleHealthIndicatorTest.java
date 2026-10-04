package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.health.contributor.Status;

/**
 * The sweep schedule's health, in the liveness group: the schedule's own status, and {@code UP} when
 * no sweep runs on this pod (the subscription or the sweep switched off).
 */
@DisplayName("sweep schedule health indicator")
class SweepScheduleHealthIndicatorTest {

    @Test
    void indicator_should_report_the_schedule_s_status() {
        final SweepSchedule schedule = mock(SweepSchedule.class);
        when(schedule.status()).thenReturn(Status.DOWN);

        final Status status = new SweepScheduleHealthIndicator(new StaticListableBeanFactory(
                Map.of("sweepSchedule", schedule)).getBeanProvider(SweepSchedule.class)).health().getStatus();

        assertThat(status).isEqualTo(Status.DOWN);
    }

    @Test
    void indicator_with_no_schedule_should_be_up() {
        final Status status = new SweepScheduleHealthIndicator(new StaticListableBeanFactory()
                .getBeanProvider(SweepSchedule.class)).health().getStatus();

        assertThat(status).isEqualTo(Status.UP);
    }
}
