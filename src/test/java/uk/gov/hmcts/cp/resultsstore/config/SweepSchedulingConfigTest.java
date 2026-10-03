package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.ExtractionSweep;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;

/**
 * The sweep's wiring (FR-037, FR-046): with the subscription and the sweep both enabled, the sweep
 * runs its rounds on a scheduler thread of its own, and a round that throws is logged by class and
 * the next round still comes; with either switched off, no sweep bean exists. No scheduler or executor
 * is exposed as a bean, so Boot's own task executor is left as it was.
 */
@DisplayName("sweep wiring")
class SweepSchedulingConfigTest {

    /** Every thread the database was called on; each call fails, as no database is here. */
    private final List<String> databaseThreads = new CopyOnWriteArrayList<>();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IntakeConfig.class, SweepSchedulingConfig.class)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class, invocation -> {
                databaseThreads.add(Thread.currentThread().getName());
                throw new IllegalStateException("no database in this test");
            }))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            // Enrichment is not under test here; on, it would need a progression base URL and user.
            .withPropertyValues("resultsstore.enrichment.enabled=false");

    @Test
    void enabled_sweep_should_run_its_rounds_on_its_own_thread_and_survive_a_failed_round() {
        try (CapturedLog log = CapturedLog.forClass(SweepSchedulingConfig.class)) {
            runner.withPropertyValues("resultsstore.publicevents.enabled=true", "resultsstore.sweep.enabled=true",
                    "resultsstore.sweep.initial-delay=0s", "resultsstore.sweep.fixed-delay=10s").run(context -> {
                        assertThat(context)
                                .hasNotFailed()
                                .hasSingleBean(ExtractionSweep.class)
                                .hasSingleBean(SweepSchedule.class)
                                .doesNotHaveBean(TaskScheduler.class)
                                .doesNotHaveBean(Executor.class);
                        await().atMost(Duration.ofSeconds(10)).until(() -> !log.messages().isEmpty());
                        assertThat(databaseThreads).isNotEmpty()
                                .allSatisfy(thread -> assertThat(thread).startsWith("extraction-sweep-"));
                        assertThat(log.messages()).first().asString()
                                .contains("Extraction sweep round failed")
                                .contains(IllegalStateException.class.getName())
                                .doesNotContain("no database in this test");
                        assertThat(context.getBean(MeterRegistry.class).counter("resultsstore.sweep.rounds.failed")
                                .count()).isPositive();
                        assertThat(context.getBean(SweepSchedule.class).isRunning()).isTrue();
                    });
        }
    }

    @Test
    void stopped_context_should_stop_the_schedule() {
        runner.withPropertyValues("resultsstore.publicevents.enabled=true", "resultsstore.sweep.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SweepSchedule.class);
                    final SweepSchedule schedule = context.getBean(SweepSchedule.class);
                    assertThat(schedule.isRunning()).isTrue();
                    context.stop();
                    assertThat(schedule.isRunning()).isFalse();
                });
    }

    @Test
    void disabled_sweep_should_create_no_sweep_bean() {
        runner.withPropertyValues("resultsstore.publicevents.enabled=true", "resultsstore.sweep.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ExtractionSweep.class)
                        .doesNotHaveBean(SweepSchedule.class));
    }

    @Test
    void disabled_subscription_should_create_no_sweep_bean() {
        runner.withPropertyValues("resultsstore.publicevents.enabled=false", "resultsstore.sweep.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ExtractionSweep.class)
                        .doesNotHaveBean(SweepSchedule.class));
    }
}
