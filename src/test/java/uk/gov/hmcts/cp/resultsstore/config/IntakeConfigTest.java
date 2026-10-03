package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.adapter.progression.NoRedirectRequestFactory;
import uk.gov.hmcts.cp.resultsstore.adapter.progression.ProgressionApplicationClient;
import uk.gov.hmcts.cp.resultsstore.adapter.publicevents.HearingResultedEventListener;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.application.IntakeService;
import uk.gov.hmcts.cp.resultsstore.application.ProgressionApplications;
import uk.gov.hmcts.cp.resultsstore.application.ShareStore;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcShareStore;

/**
 * The intake's wiring: with the subscription enabled, every bean the listener needs comes from
 * {@link IntakeConfig} itself, so the service starts with no stand-in (the compose stack and the
 * container smoke start it that way); with it disabled, none is created.
 */
@DisplayName("intake wiring")
class IntakeConfigTest {

    private static final String BASE_URL = "resultsstore.progression.base-url=http://progression.example";

    private static final String SYSTEM_USER_ID =
            "resultsstore.progression.system-user-id=6f1c2c7e-3a4b-4c5d-8e9f-0a1b2c3d4e5f";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IntakeConfig.class)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void enabled_subscription_should_wire_the_listener_with_the_real_store_and_an_observer() {
        runner.withPropertyValues("resultsstore.publicevents.enabled=true", BASE_URL, SYSTEM_USER_ID)
                .run(context -> {
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

    @Test
    void enabled_subscription_and_enrichment_should_build_the_progression_client() {
        runner.withPropertyValues("resultsstore.publicevents.enabled=true", "resultsstore.enrichment.enabled=true",
                BASE_URL, SYSTEM_USER_ID, "resultsstore.progression.connect-timeout=3s",
                "resultsstore.progression.read-timeout=7s").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ProgressionApplications.class)
                            .hasSingleBean(NoRedirectRequestFactory.class);
                    assertThat(context.getBean(ProgressionApplications.class))
                            .isInstanceOf(ProgressionApplicationClient.class);
                    final NoRedirectRequestFactory factory = context.getBean(NoRedirectRequestFactory.class);
                    assertThat(factory.getConnectionConfig().getConnectTimeout()).isEqualTo(Timeout.ofSeconds(3));
                    assertThat(factory.getConnectionConfig().getSocketTimeout()).isEqualTo(Timeout.ofSeconds(7));
                    assertThat(factory.getResponseDeadline()).isEqualTo(Duration.ofSeconds(7));
                });
    }

    @Test
    void disabled_enrichment_should_build_no_progression_client_and_allow_blank_settings() {
        runner.withPropertyValues("resultsstore.publicevents.enabled=true", "resultsstore.enrichment.enabled=false",
                        "resultsstore.progression.base-url=", "resultsstore.progression.system-user-id=")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(IntakeService.class)
                        .doesNotHaveBean(ProgressionApplications.class));
    }

    @Test
    void disabled_subscription_should_build_no_progression_client_and_allow_blank_settings() {
        runner.withPropertyValues("resultsstore.publicevents.enabled=false", "resultsstore.enrichment.enabled=true",
                        "resultsstore.progression.base-url=", "resultsstore.progression.system-user-id=")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ProgressionApplications.class));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        resultsstore.progression.base-url=   | resultsstore.progression.base-url must be set
        resultsstore.progression.system-user-id= | resultsstore.progression.system-user-id must be set
        """)
    void blank_setting_with_enrichment_on_should_stop_the_service_starting(final String blank,
            final String refusal) {
        runner.withPropertyValues("resultsstore.publicevents.enabled=true", "resultsstore.enrichment.enabled=true",
                        BASE_URL, SYSTEM_USER_ID)
                .withPropertyValues(blank)
                .run(context -> assertThat(context).getFailure().rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageStartingWith(refusal)
                        .hasMessageNotContaining("progression.example")
                        .hasMessageNotContaining("6f1c2c7e-3a4b-4c5d-8e9f-0a1b2c3d4e5f"));
    }
}
