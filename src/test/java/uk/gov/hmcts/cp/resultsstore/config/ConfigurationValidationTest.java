package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Every rule in contracts/configuration.md refuses a bad value when the service starts (FR-046), with
 * application.yaml loaded as the service loads it.
 */
@DisplayName("configuration rules")
class ConfigurationValidationTest {

    private static final String STATEMENT_BELOW_SOCKET = "resultsstore.intake.store.statement-timeout must be below";

    private static final String BASE_URL = "resultsstore.progression.base-url=http://progression.example";

    private static final String SYSTEM_USER_ID =
            "resultsstore.progression.system-user-id=6f1c2c7e-3a4b-4c5d-8e9f-0a1b2c3d4e5f";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(IntakeConfig.class)
            // The intake beans need a database; only the settings are under test here.
            .withPropertyValues("resultsstore.publicevents.enabled=false");

    @Test
    void defaults_should_be_those_of_the_contract() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            final IntakeProperties intake = context.getBean(IntakeProperties.class);
            assertThat(intake.redeliveryPause().enabled()).isTrue();
            assertThat(intake.redeliveryPause().cap()).isEqualTo(Duration.ofSeconds(30));
            assertThat(intake.receiptTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(intake.store().transactionTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(intake.store().lockTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(intake.store().statementTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(intake.store().idleInTransactionTimeout()).isEqualTo(Duration.ofSeconds(10));
            final SweepProperties sweep = context.getBean(SweepProperties.class);
            assertThat(sweep.enabled()).isTrue();
            assertThat(sweep.initialDelay()).isEqualTo(Duration.ofMinutes(1));
            assertThat(sweep.fixedDelay()).isEqualTo(Duration.ofMinutes(5));
            assertThat(sweep.batchSize()).isEqualTo(100);
            assertThat(sweep.maxAttempts()).isEqualTo(3);
        });
    }

    /** Spec 003 (E3, FR-061): statement 20 s → 10 s and lock 10 s → 5 s, so the 90 s lag holds. */
    @Test
    void the_intake_store_defaults_should_be_60s_10s_5s_10s() {
        runner.run(context -> {
            final IntakeProperties.Store store = context.getBean(IntakeProperties.class).store();
            assertThat(List.of(store.transactionTimeout(), store.statementTimeout(), store.lockTimeout(),
                    store.idleInTransactionTimeout())).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(10),
                    Duration.ofSeconds(5), Duration.ofSeconds(10));
        });
        // The record's own defaults, used when application.yaml is not loaded, agree with the file.
        new ApplicationContextRunner().withUserConfiguration(IntakeConfig.class)
                .withPropertyValues("resultsstore.publicevents.enabled=false")
                .run(context -> {
                    final IntakeProperties.Store store = context.getBean(IntakeProperties.class).store();
                    assertThat(List.of(store.transactionTimeout(), store.statementTimeout(), store.lockTimeout(),
                            store.idleInTransactionTimeout())).containsExactly(Duration.ofSeconds(60),
                            Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(10));
                });
    }

    @Test
    void a_lock_timeout_above_the_statement_timeout_should_still_stop_the_service() {
        runner.withPropertyValues("resultsstore.intake.store.lock-timeout=11s")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageStartingWith("resultsstore.intake.store.lock-timeout must not exceed "
                                + "resultsstore.intake.store.statement-timeout"));
    }

    @Test
    void subscription_should_keep_its_topic_name_and_selector() {
        runner.run(context -> {
            assertThat(context.getEnvironment().getProperty("resultsstore.publicevents.topic"))
                    .isEqualTo("public.event");
            assertThat(context.getEnvironment().getProperty("resultsstore.publicevents.subscription"))
                    .isEqualTo("resultsstore-service.sdg");
            assertThat(context.getEnvironment().getProperty("resultsstore.publicevents.selector"))
                    .isEqualTo("CPPNAME = 'public.events.hearing.hearing-resulted'");
            assertThat(context.getEnvironment().getProperty("spring.jms.pub-sub-domain")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("spring.jms.subscription-durable")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("spring.jms.client-id")).isNull();
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        resultsstore.intake.redelivery-pause.cap=999ms | resultsstore.intake.redelivery-pause.cap must be from
        resultsstore.intake.redelivery-pause.cap=301s | resultsstore.intake.redelivery-pause.cap must be from
        resultsstore.intake.receipt-timeout=999ms | resultsstore.intake.receipt-timeout must be from
        resultsstore.intake.receipt-timeout=61s | resultsstore.intake.receipt-timeout must be from
        resultsstore.intake.store.lock-timeout=0s | resultsstore.intake.store.lock-timeout must be above zero
        resultsstore.intake.store.lock-timeout=21s | resultsstore.intake.store.lock-timeout must not exceed resultsstore.intake.store.statement-timeout
        resultsstore.intake.store.statement-timeout=0s | resultsstore.intake.store.statement-timeout must be above zero
        resultsstore.intake.store.statement-timeout=-1s | resultsstore.intake.store.statement-timeout must be above zero
        resultsstore.intake.store.idle-in-transaction-timeout=0s | resultsstore.intake.store.idle-in-transaction-timeout must be above zero
        resultsstore.intake.store.idle-in-transaction-timeout=61s | resultsstore.intake.store.idle-in-transaction-timeout must not exceed resultsstore.intake.store.transaction-timeout
        resultsstore.intake.store.transaction-timeout=9s | resultsstore.intake.store.statement-timeout must not exceed resultsstore.intake.store.transaction-timeout
        resultsstore.sweep.initial-delay=-1s | resultsstore.sweep.initial-delay must be at least
        resultsstore.sweep.fixed-delay=9s | resultsstore.sweep.fixed-delay must be from
        resultsstore.sweep.fixed-delay=25h | resultsstore.sweep.fixed-delay must be from
        resultsstore.sweep.batch-size=0 | resultsstore.sweep.batch-size must be from
        resultsstore.sweep.batch-size=1001 | resultsstore.sweep.batch-size must be from
        resultsstore.sweep.max-attempts=0 | resultsstore.sweep.max-attempts must be from
        resultsstore.sweep.max-attempts=11 | resultsstore.sweep.max-attempts must be from
        """)
    void bad_value_should_stop_the_service_starting(final String setting, final String refusal) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).getFailure().rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(refusal));
    }

    /**
     * A Spring transaction timeout is whole seconds and PostgreSQL's limits are whole milliseconds; a value
     * either would round would make the visibility bound differ from the limits actually enforced (R4).
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        transaction-timeout=500ms;statement-timeout=200ms;lock-timeout=100ms;idle-in-transaction-timeout=100ms | resultsstore.intake.store.transaction-timeout must be a whole number of seconds
        transaction-timeout=60500ms | resultsstore.intake.store.transaction-timeout must be a whole number of seconds
        statement-timeout=10500us | resultsstore.intake.store.statement-timeout must be a whole number of milliseconds
        statement-timeout=500us | resultsstore.intake.store.statement-timeout must be a whole number of milliseconds
        lock-timeout=4999999ns | resultsstore.intake.store.lock-timeout must be a whole number of milliseconds
        idle-in-transaction-timeout=500us | resultsstore.intake.store.idle-in-transaction-timeout must be a whole number of milliseconds
        """)
    void an_intake_store_timeout_the_enforcing_side_would_round_should_stop_the_service_starting(
            final String settings, final String refusal) {
        runner.withPropertyValues(storeSettings(settings)).run(context -> assertThat(context).getFailure()
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(refusal));
    }

    @Test
    void whole_second_and_whole_millisecond_store_timeouts_should_be_accepted() {
        runner.withPropertyValues(storeSettings(
                        "transaction-timeout=1s;statement-timeout=1ms;lock-timeout=1ms;idle-in-transaction-timeout=999ms"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static String[] storeSettings(final String settings) {
        return Arrays.stream(settings.split(";")).map(setting -> "resultsstore.intake.store." + setting)
                .toArray(String[]::new);
    }

    @Test
    void statement_timeout_at_or_above_the_socket_timeout_should_stop_the_service_starting() {
        runner.withPropertyValues("resultsstore.intake.store.statement-timeout=30s")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith(STATEMENT_BELOW_SOCKET));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "resultsstore.intake.redelivery-pause.cap=1s",
        "resultsstore.intake.redelivery-pause.cap=5m",
        "resultsstore.intake.redelivery-pause.enabled=false",
        "resultsstore.intake.receipt-timeout=1s",
        "resultsstore.intake.receipt-timeout=60s",
        "resultsstore.intake.store.lock-timeout=10s",
        "resultsstore.intake.store.statement-timeout=29s",
        "resultsstore.intake.store.idle-in-transaction-timeout=60s",
        "resultsstore.intake.store.transaction-timeout=10s",
        "resultsstore.sweep.enabled=false",
        "resultsstore.sweep.initial-delay=0s",
        "resultsstore.sweep.fixed-delay=10s",
        "resultsstore.sweep.fixed-delay=24h",
        "resultsstore.sweep.batch-size=1",
        "resultsstore.sweep.batch-size=1000",
        "resultsstore.sweep.max-attempts=1",
        "resultsstore.sweep.max-attempts=10"
    })
    void value_at_a_boundary_should_be_accepted(final String setting) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void statement_timeout_should_be_free_of_the_socket_timeout_when_none_is_set() {
        runner.withPropertyValues("spring.datasource.hikari.data-source-properties.socketTimeout=0",
                        "resultsstore.intake.store.statement-timeout=45s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void statement_timeout_should_be_free_of_the_socket_timeout_when_its_property_is_empty() {
        runner.withPropertyValues("spring.datasource.hikari.data-source-properties.socketTimeout=",
                        "resultsstore.intake.store.statement-timeout=45s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void statement_timeout_should_stay_below_a_longer_socket_timeout() {
        runner.withPropertyValues("spring.datasource.hikari.data-source-properties.socketTimeout=60",
                        "resultsstore.intake.store.statement-timeout=45s")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.datasource.hikari.data-source-properties.socketTimeout=60",
                        "resultsstore.intake.store.statement-timeout=60s")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith(STATEMENT_BELOW_SOCKET));
    }

    @Test
    void enrichment_defaults_should_be_those_of_the_contract() {
        runner.withPropertyValues(BASE_URL, SYSTEM_USER_ID).run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(EnrichmentProperties.class)
                    .hasSingleBean(ProgressionProperties.class);
            assertThat(context.getBean(EnrichmentProperties.class).enabled()).isTrue();
            final ProgressionProperties progression = context.getBean(ProgressionProperties.class);
            assertThat(progression.baseUrl()).isEqualTo("http://progression.example");
            assertThat(progression.systemUserId()).isEqualTo("6f1c2c7e-3a4b-4c5d-8e9f-0a1b2c3d4e5f");
            assertThat(progression.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(progression.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        resultsstore.progression.base-url=localhost:8080 | resultsstore.progression.base-url must be
        resultsstore.progression.base-url=ftp://x | resultsstore.progression.base-url must be
        resultsstore.progression.base-url=http://x/path | resultsstore.progression.base-url must be
        resultsstore.progression.base-url=http://x?q=1 | resultsstore.progression.base-url must be
        resultsstore.progression.base-url=http://x#f | resultsstore.progression.base-url must be
        resultsstore.progression.base-url=http://user:secret@x | resultsstore.progression.base-url must be
        resultsstore.progression.base-url=http://[::1 | resultsstore.progression.base-url must be
        resultsstore.progression.connect-timeout=0s | resultsstore.progression.connect-timeout must be from
        resultsstore.progression.connect-timeout=31s | resultsstore.progression.connect-timeout must be from
        resultsstore.progression.read-timeout=0s | resultsstore.progression.read-timeout must be from
        resultsstore.progression.read-timeout=61s | resultsstore.progression.read-timeout must be from
        resultsstore.progression.system-user-id=not-a-uuid | resultsstore.progression.system-user-id must be
        """)
    void bad_progression_value_should_stop_the_service_starting_without_naming_the_value(final String setting,
            final String refusal) {
        final String value = setting.substring(setting.indexOf('=') + 1);
        runner.withPropertyValues(BASE_URL, SYSTEM_USER_ID).withPropertyValues(setting)
                .run(context -> assertThat(context).getFailure().rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageStartingWith(refusal)
                        .hasMessageNotContaining(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "resultsstore.progression.base-url=http://x",
        "resultsstore.progression.base-url=http://x/",
        "resultsstore.progression.base-url=https://x:8443",
        "resultsstore.progression.connect-timeout=1s",
        "resultsstore.progression.connect-timeout=30s",
        "resultsstore.progression.read-timeout=1s",
        "resultsstore.progression.read-timeout=60s"
    })
    void progression_value_at_a_boundary_should_be_accepted(final String setting) {
        runner.withPropertyValues(BASE_URL, SYSTEM_USER_ID).withPropertyValues(setting)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProgressionProperties.class));
    }

    @Test
    void settings_should_print_their_timeouts_but_neither_the_base_url_nor_the_system_user_id() {
        final ProgressionProperties settings = new ProgressionProperties("http://progression.example",
                "6f1c2c7e-3a4b-4c5d-8e9f-0a1b2c3d4e5f", Duration.ofSeconds(5), Duration.ofSeconds(10));

        assertThat(settings.toString())
                .contains("connectTimeout=PT5S", "readTimeout=PT10S")
                .doesNotContain("progression.example")
                .doesNotContain("6f1c2c7e-3a4b-4c5d-8e9f-0a1b2c3d4e5f");
    }

    @Test
    void blank_base_url_and_user_id_should_be_accepted_with_enrichment_off() {
        runner.withPropertyValues("resultsstore.enrichment.enabled=false", "resultsstore.progression.base-url=",
                        "resultsstore.progression.system-user-id=")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EnrichmentProperties.class);
                    assertThat(context.getBean(EnrichmentProperties.class).enabled()).isFalse();
                });
    }
}
