package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.application.ShareQueries;
import uk.gov.hmcts.cp.resultsstore.application.ShareReadService;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcShareQueries;

/**
 * The read API's wiring (specs/003-read-api research R1, R6): the read beans are built whatever the
 * subscription switch says, the read template carries the read statement timeout, and the service holds pulls
 * back by the effective lag.
 */
@DisplayName("read API wiring")
class ReadApiConfigTest {

    /** Building a template opens no connection, so the URL is never used. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IntakeConfig.class, ReadApiConfig.class)
            .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:postgresql://unused.invalid/none"))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("resultsstore.publicevents.enabled=false");

    @Test
    void the_read_beans_should_exist_with_the_subscription_off() {
        runner.run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(ShareReadService.class)
                    .hasSingleBean(ShareQueries.class)
                    .hasSingleBean(ReadObserver.class)
                    .hasSingleBean(VisibilityLag.class);
            assertThat(context.getBean(ShareQueries.class)).isInstanceOf(JdbcShareQueries.class);
            assertThat(context.getBean(ReadObserver.class)).isInstanceOf(MicrometerReadObserver.class);
        });
    }

    @Test
    void the_read_template_should_carry_the_statement_timeout() {
        final DataSource dataSource = new DriverManagerDataSource("jdbc:postgresql://unused.invalid/none");

        assertThat(ReadApiConfig.readTemplate(dataSource, Duration.ofSeconds(5)).getQueryTimeout()).isEqualTo(5);
        // A JDBC query timeout is whole seconds: a part second is rounded up, never down to none.
        assertThat(ReadApiConfig.readTemplate(dataSource, Duration.ofMillis(1500)).getQueryTimeout()).isEqualTo(2);
        assertThat(ReadApiConfig.readTemplate(dataSource, Duration.ofMillis(1)).getQueryTimeout()).isEqualTo(1);
        assertThat(ReadApiConfig.readTemplate(dataSource, Duration.ofSeconds(5)).getDataSource())
                .isSameAs(dataSource);
    }

    @Test
    void the_service_should_use_the_effective_lag() {
        runner.run(context -> assertThat(context.getBean(ShareReadService.class).visibilityLag())
                .isEqualTo(Duration.ofSeconds(90)));
        runner.withPropertyValues("resultsstore.read.pull.visibility-lag=200s")
                .run(context -> {
                    assertThat(context.getBean(ShareReadService.class).visibilityLag())
                            .isEqualTo(Duration.ofSeconds(200));
                    assertThat(context.getBean(VisibilityLag.class).value()).isEqualTo(Duration.ofSeconds(200));
                });
    }
}
