package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

/**
 * Every pooled connection starts with {@code statement_timeout} equal to the intake statement timeout
 * (specs/003-read-api FR-062): the backstop for anything that forgets its own limit. Flyway, which runs first
 * with its limit lifted, migrates on its own connection, so none of the pool's carries that lifted value.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("the pool's statement timeout")
class PooledStatementTimeoutIT {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void database(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Test
    void a_pooled_connection_should_report_the_intake_statement_timeout() throws SQLException {
        assertThat(everyPooledConnectionsTimeout()).isNotEmpty().containsOnly("10s");
        assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("10s");
    }

    @Test
    void the_store_transactions_own_setting_should_still_win_inside_it() {
        final String inside = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.sql("SELECT set_config('statement_timeout', '3000ms', TRUE)").query(String.class).single();
            return jdbc.sql("SHOW statement_timeout").query(String.class).single();
        });

        assertThat(inside).isEqualTo("3s");
        assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("10s");
    }

    /** Borrows every connection the pool holds at once and reads each one's setting. */
    private List<String> everyPooledConnectionsTimeout() throws SQLException {
        final List<String> timeouts = new ArrayList<>();
        borrowAndRead(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize(), timeouts);
        return timeouts;
    }

    /** Holds one connection while the rest are borrowed, so no connection is read twice. */
    private void borrowAndRead(final int remaining, final List<String> timeouts) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement show = connection.createStatement();
                ResultSet value = show.executeQuery("SHOW statement_timeout")) {
            if (value.next()) {
                timeouts.add(value.getString(1));
            }
            if (remaining > 1) {
                borrowAndRead(remaining - 1, timeouts);
            }
        }
    }

    @Nested
    @TestPropertySource(properties = "resultsstore.intake.store.statement-timeout=7s")
    @DisplayName("with a custom intake statement timeout")
    class CustomValue {

        @Autowired
        private JdbcClient customJdbc;

        @Test
        void a_custom_intake_statement_timeout_should_reach_the_pool() {
            assertThat(customJdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("7s");
        }
    }
}
