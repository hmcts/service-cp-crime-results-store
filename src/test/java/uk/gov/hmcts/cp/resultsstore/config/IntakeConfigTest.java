package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.adapter.publicevents.HearingResultedEventListener;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.application.IntakeService;
import uk.gov.hmcts.cp.resultsstore.application.ShareStore;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcShareStore;

/**
 * The intake's wiring: with the subscription enabled, every bean the listener needs comes from
 * {@link IntakeConfig} itself, so the service starts with no stand-in (the compose stack and the
 * container smoke start it that way); with it disabled, none is created.
 */
@DisplayName("intake wiring")
class IntakeConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IntakeConfig.class)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void enabled_subscription_should_wire_the_listener_with_the_real_store_and_an_observer() {
        runner.withPropertyValues("resultsstore.publicevents.enabled=true").run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(HearingResultedEventListener.class)
                    .hasSingleBean(IntakeService.class)
                    .hasSingleBean(IntakeObserver.class)
                    .hasSingleBean(ShareStore.class);
            assertThat(context.getBean(ShareStore.class)).isInstanceOf(JdbcShareStore.class);
            assertThat(context.getBean(IntakeObserver.class)).isInstanceOf(MicrometerIntakeObserver.class);
        });
    }

    @Test
    void disabled_subscription_should_create_no_intake_bean() {
        runner.withPropertyValues("resultsstore.publicevents.enabled=false").run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(HearingResultedEventListener.class)
                .doesNotHaveBean(IntakeService.class)
                .doesNotHaveBean(IntakeObserver.class)
                .doesNotHaveBean(ShareStore.class));
    }
}
