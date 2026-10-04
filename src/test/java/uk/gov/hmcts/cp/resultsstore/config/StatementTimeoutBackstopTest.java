package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/** The pool backstop (specs/003-read-api FR-062; contracts/configuration.md *Pool backstop*). */
@DisplayName("the pool's statement timeout backstop")
class StatementTimeoutBackstopTest {

    private static final String PROPERTY = "resultsstore.intake.store.statement-timeout";

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "10s   | SET statement_timeout = '10000ms'",
        "1m    | SET statement_timeout = '60000ms'",
        "500ms | SET statement_timeout = '500ms'",
        "PT7S  | SET statement_timeout = '7000ms'"
    })
    void the_init_sql_should_be_set_statement_timeout_in_milliseconds_of_the_intake_property(final String value,
            final String expected) {
        try (HikariDataSource pool = new HikariDataSource()) {
            // A lock timeout at most the statement timeout, as the intake rules require.
            final Object processed = backstop(new MockEnvironment().withProperty(PROPERTY, value)
                    .withProperty("resultsstore.intake.store.lock-timeout", "100ms"))
                    .postProcessAfterInitialization(pool, "dataSource");

            assertThat(processed).isSameAs(pool);
            assertThat(pool.getConnectionInitSql()).isEqualTo(expected);
        }
    }

    @Test
    void the_init_sql_should_follow_the_intake_default_when_the_property_is_unset() {
        try (HikariDataSource pool = new HikariDataSource()) {
            backstop(new MockEnvironment()).postProcessAfterInitialization(pool, "dataSource");

            assertThat(pool.getConnectionInitSql())
                    .isEqualTo(StatementTimeoutBackstop.initSql(Duration.ofSeconds(10)))
                    .isEqualTo("SET statement_timeout = '10000ms'");
        }
    }

    @Test
    void a_non_hikari_data_source_should_be_left_alone() {
        final DriverManagerDataSource plain = new DriverManagerDataSource("jdbc:postgresql://unused/none");
        final Object other = new Object();

        assertThat(backstop(new MockEnvironment()).postProcessAfterInitialization(plain, "plain")).isSameAs(plain);
        assertThat(backstop(new MockEnvironment()).postProcessAfterInitialization(other, "other")).isSameAs(other);
    }

    @Test
    void an_existing_init_sql_should_stop_the_service_naming_the_property() {
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setConnectionInitSql("SET statement_timeout = '99s'");

            assertThatThrownBy(() -> backstop(new MockEnvironment()).postProcessAfterInitialization(pool, "dataSource"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("spring.datasource.hikari.connection-init-sql must not be set: the pool's "
                            + "statement_timeout is set from resultsstore.intake.store.statement-timeout")
                    .hasMessageNotContaining("99s");
        }
    }

    /** An empty or blank value (an empty environment variable) is no init SQL, so the backstop sets its own. */
    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void a_blank_init_sql_should_be_treated_as_unset(final String blank) {
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setConnectionInitSql(blank);

            backstop(new MockEnvironment()).postProcessAfterInitialization(pool, "dataSource");

            assertThat(pool.getConnectionInitSql()).isEqualTo("SET statement_timeout = '10000ms'");
        }
    }

    private static StatementTimeoutBackstop backstop(final MockEnvironment environment) {
        return new StatementTimeoutBackstop(environment);
    }
}
