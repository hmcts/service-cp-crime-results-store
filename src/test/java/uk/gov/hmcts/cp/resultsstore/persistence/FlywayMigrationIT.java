package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationIT {

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void store(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Test
    void startup_should_apply_v1() {
        final List<String> applied = jdbc.sql(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class)
                .list();

        assertThat(applied).containsExactly("1");
    }

    @Test
    void event_receipt_should_exist_with_its_identity_columns() {
        final List<String> columns = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_name = 'event_receipt' ORDER BY ordinal_position""")
                .query(String.class)
                .list();

        assertThat(columns).containsExactly("hearing_id", "hearing_day", "shared_time", "received_at");
    }

    @Test
    void event_receipt_should_refuse_a_duplicate_share() {
        final String insert = "INSERT INTO event_receipt (hearing_id, hearing_day, shared_time) "
                + "VALUES ('6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11', DATE '2026-09-30', "
                + "TIMESTAMPTZ '2026-09-30T15:04:05Z') ON CONFLICT DO NOTHING";

        jdbc.sql("DELETE FROM event_receipt").update();
        final int first = jdbc.sql(insert).update();
        final int second = jdbc.sql(insert).update();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }
}
