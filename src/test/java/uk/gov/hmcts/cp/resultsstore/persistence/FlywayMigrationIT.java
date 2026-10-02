package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

/**
 * Holds the schema to its rules (data-model.md, contracts/schema.md): every rule the database
 * enforces is proved here by a statement it accepts or refuses.
 *
 * <p>Each test works on its own random hearing and message ids, so the suites sharing the
 * container never see each other's rows.
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationIT {

    /** SQLSTATE of a CHECK violation. */
    private static final String CHECK_VIOLATION = "23514";

    /** SQLSTATE of a UNIQUE or PRIMARY KEY violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    /** SQLSTATE PostgreSQL gives for a value written to a GENERATED ALWAYS identity column. */
    private static final String GENERATED_ALWAYS = "428C9";

    private static final String SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final String HEARING_DAY = "2026-10-02";

    private static final String SHARED_AT = "2026-10-02T14:19:50.706Z";

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void store(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @BeforeEach
    void schemaIsAtV3() {
        // Every rule below needs the V2 and V3 tables; say so as an assertion, not as SQL noise.
        assertThat(appliedVersions()).as("applied migrations").contains("2", "3");
    }

    @Test
    void startup_on_an_empty_database_should_apply_v1_to_v3() {
        assertThat(appliedVersions()).containsExactly("1", "2", "3");
    }

    @Test
    void v2_on_a_non_empty_v1_event_receipt_should_refuse_to_run() {
        final String schema = "v2_guard_" + UUID.randomUUID().toString().replace("-", "");
        flyway(schema).target("1").load().migrate();
        jdbc.sql("INSERT INTO " + schema + ".event_receipt (hearing_id, hearing_day, shared_time) "
                        + "VALUES (:hearingId, DATE '2026-09-30', TIMESTAMPTZ '2026-09-30T15:04:05Z')")
                .param("hearingId", UUID.randomUUID())
                .update();

        assertThatThrownBy(() -> flyway(schema).load().migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V2 refuses to reshape it");
        assertThat(jdbc.sql("SELECT count(*) FROM " + schema + ".event_receipt").query(Integer.class).single())
                .as("the V1 row survives the refused migration")
                .isEqualTo(1);
    }

    @Nested
    @DisplayName("event_receipt")
    class EventReceipt {

        @Test
        void insert_with_a_status_outside_the_five_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_status_ck",
                    receipt("'SKIPPED'", whole(), "NULL", "clock_timestamp()", "'X'", "NULL"));
        }

        @Test
        void insert_of_a_stored_receipt_with_message_text_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_text_only_for_non_share_ck",
                    receipt("'STORED'", whole(), "'" + UUID.randomUUID() + "'", "clock_timestamp()",
                            "NULL", "'{\"a\":1}'"));
        }

        @Test
        void insert_of_a_received_receipt_with_settled_at_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_settled_ck",
                    receipt("'RECEIVED'", whole(), "NULL", "clock_timestamp()", "NULL", "NULL"));
        }

        @Test
        void insert_of_an_end_state_without_settled_at_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_settled_ck",
                    receipt("'UNREADABLE'", "NULL", "NULL", "NULL", "'NOT_JSON'", "'not json'"));
        }

        @Test
        void insert_of_a_received_receipt_without_settled_at_should_be_accepted() {
            assertAccepted(receipt("'RECEIVED'", whole(), "NULL", "NULL", "NULL", "NULL"));
        }

        @Test
        void insert_of_a_no_identity_receipt_with_part_of_the_identity_should_be_accepted() {
            assertAccepted(receipt("'NO_IDENTITY'", "'" + UUID.randomUUID() + "', NULL, NULL", "NULL",
                    "clock_timestamp()", "'MISSING_HEARING_DAY'", "'{\"hearing\":{}}'"));
        }

        @Test
        void insert_of_a_non_share_without_a_reason_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_non_share_reason_ck",
                    receipt("'NO_IDENTITY'", "NULL", "NULL", "clock_timestamp()", "NULL", "'{}'"));
        }

        @Test
        void insert_of_a_stored_receipt_without_a_share_id_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_share_id_ck",
                    receipt("'STORED'", whole(), "NULL", "clock_timestamp()", "NULL", "NULL"));
        }

        @Test
        void insert_of_a_received_receipt_without_its_whole_identity_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_identity_whole_ck",
                    receipt("'RECEIVED'", "'" + UUID.randomUUID() + "', NULL, NULL", "NULL", "NULL",
                            "NULL", "NULL"));
        }

        @Test
        void insert_of_a_second_receipt_for_one_message_id_should_be_refused() {
            final String messageId = "ID:" + UUID.randomUUID();
            final String insert = "INSERT INTO event_receipt (message_id, status, hearing_id, hearing_day, "
                    + "shared_at) VALUES ('" + messageId + "', 'RECEIVED', " + whole() + ")";
            assertAccepted(insert);

            assertRefused(UNIQUE_VIOLATION, "event_receipt_pk", insert);
        }

        private String receipt(final String status, final String identity, final String shareId,
                final String settledAt, final String reason, final String messageText) {
            final String identityValues = "NULL".equals(identity) ? "NULL, NULL, NULL" : identity;
            return "INSERT INTO event_receipt (message_id, status, hearing_id, hearing_day, shared_at, "
                    + "share_id, settled_at, reason, message_text) VALUES ('ID:" + UUID.randomUUID() + "', "
                    + status + ", " + identityValues + ", " + shareId + ", " + settledAt + ", " + reason
                    + ", " + messageText + ")";
        }

        private String whole() {
            return "'" + UUID.randomUUID() + "', DATE '" + HEARING_DAY + "', TIMESTAMPTZ '" + SHARED_AT + "'";
        }
    }

    @Nested
    @DisplayName("hearing_share and its children")
    class HearingShare {

        private UUID hearingId;

        @BeforeEach
        void aHearingDay() {
            hearingId = UUID.randomUUID();
            jdbc.sql("INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (:h, DATE '"
                            + HEARING_DAY + "')")
                    .param("h", hearingId)
                    .update();
        }

        @Test
        void insert_of_a_share_with_its_whole_row_should_be_accepted() {
            assertAccepted(share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'",
                    "'OK'", "NULL", "NULL"));
        }

        @Test
        void insert_of_a_share_whose_identity_is_already_stored_should_insert_no_row() {
            jdbc.sql(share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL"))
                    .update();

            final int inserted = jdbc.sql(share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'",
                            "'OK'", "NULL", "NULL")
                    + " ON CONFLICT (hearing_id, hearing_day, shared_at) DO NOTHING").update();

            assertThat(inserted).isZero();
        }

        @Test
        void insert_of_a_share_whose_identity_is_already_stored_without_on_conflict_should_be_refused() {
            jdbc.sql(share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL"))
                    .update();

            assertRefused(UNIQUE_VIOLATION, "hearing_share_identity_uk",
                    share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL"));
        }

        @Test
        void insert_of_a_second_latest_share_for_one_day_should_be_refused() {
            jdbc.sql(share(UUID.randomUUID(), SHARED_AT, "TRUE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL"))
                    .update();

            assertRefused(UNIQUE_VIOLATION, "hearing_share_one_latest_ux",
                    share(UUID.randomUUID(), "2026-10-02T15:00:00.000Z", "TRUE", "'" + SHA256 + "'",
                            "'OK'", "NULL", "NULL"));
        }

        @Test
        void insert_of_a_checksum_that_is_not_lower_case_hex_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_share_sha256_ck",
                    share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256.toUpperCase(Locale.ROOT) + "'",
                            "'OK'", "NULL", "NULL"));
        }

        @Test
        void insert_of_a_checksum_of_the_wrong_length_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_share_sha256_ck",
                    share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256.substring(1) + "'",
                            "'OK'", "NULL", "NULL"));
        }

        @Test
        void insert_of_a_failed_share_without_a_reason_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_share_projection_reason_ck",
                    share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'FAILED'", "NULL", "NULL"));
        }

        @Test
        void insert_of_an_ok_share_with_a_reason_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_share_projection_reason_ck",
                    share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'",
                            "'WRONG_TYPE:hearing.isSJPHearing'", "NULL"));
        }

        @Test
        void insert_of_a_failed_share_with_its_reason_should_be_accepted() {
            assertAccepted(share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'FAILED'",
                    "'WRONG_TYPE:hearing.isSJPHearing'", "NULL"));
        }

        @Test
        void insert_of_a_failed_share_with_a_key_detail_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_share_failed_is_empty_ck",
                    share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'FAILED'",
                            "'WRONG_TYPE:hearing.isSJPHearing'", "'2577'"));
        }

        @Test
        void insert_of_a_projection_status_outside_ok_and_failed_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_share_projection_status_ck",
                    share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'PENDING'", "NULL", "NULL"));
        }

        @Test
        void insert_with_an_explicit_stored_seq_should_be_refused() {
            final String insert = share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'",
                    "NULL", "NULL").replace("(share_id,", "(stored_seq, share_id,").replace("VALUES (", "VALUES (42, ");

            assertRefused(GENERATED_ALWAYS, "stored_seq", insert);
        }

        @Test
        void insert_with_an_expiry_should_be_refused() {
            final String insert = share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'",
                    "NULL", "NULL").replace("(share_id,", "(expires_at, share_id,")
                    .replace("VALUES (", "VALUES (clock_timestamp(), ");

            assertRefused(CHECK_VIOLATION, "hearing_share_expires_unset_ck", insert);
        }

        @Test
        void insert_of_a_payload_without_its_parsed_copy_should_be_accepted() {
            final UUID shareId = UUID.randomUUID();
            jdbc.sql(share(shareId, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL")).update();

            assertAccepted("INSERT INTO hearing_share_payload (share_id, payload_text, text_bytes, payload_json) "
                    + "VALUES ('" + shareId + "', '{\"a\":\"\\u0000\"}', 14, NULL)");
        }

        @Test
        void insert_of_a_payload_whose_byte_count_is_wrong_should_be_refused() {
            final UUID shareId = UUID.randomUUID();
            jdbc.sql(share(shareId, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL")).update();

            assertRefused(CHECK_VIOLATION, "hearing_share_payload_bytes_ck",
                    "INSERT INTO hearing_share_payload (share_id, payload_text, text_bytes) "
                            + "VALUES ('" + shareId + "', '{\"a\":\"é\"}', 9)");
        }

        @Test
        void insert_of_a_repeated_case_and_defendant_for_one_share_should_be_refused() {
            final UUID shareId = UUID.randomUUID();
            jdbc.sql(share(shareId, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL")).update();
            final String insert = "INSERT INTO share_defendant (share_id, case_id, defendant_id) VALUES ('"
                    + shareId + "', 'c1c1c1c1-0000-4000-8000-000000000001', 'd1d1d1d1-0000-4000-8000-000000000001')";
            assertAccepted(insert);

            assertRefused(UNIQUE_VIOLATION, "share_defendant_pk", insert);
        }

        @Test
        void insert_of_one_defendant_on_a_second_case_should_be_accepted() {
            final UUID shareId = UUID.randomUUID();
            jdbc.sql(share(shareId, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL")).update();
            assertAccepted("INSERT INTO share_defendant (share_id, case_id, defendant_id) VALUES ('"
                    + shareId + "', 'c1c1c1c1-0000-4000-8000-000000000001', 'd1d1d1d1-0000-4000-8000-000000000001')");

            assertAccepted("INSERT INTO share_defendant (share_id, case_id, defendant_id) VALUES ('"
                    + shareId + "', 'c2c2c2c2-0000-4000-8000-000000000002', 'd1d1d1d1-0000-4000-8000-000000000001')");
        }

        @Test
        void insert_of_a_day_row_naming_a_latest_share_with_a_zero_count_should_be_refused() {
            final UUID shareId = UUID.randomUUID();
            jdbc.sql(share(shareId, SHARED_AT, "TRUE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL")).update();

            assertRefused(CHECK_VIOLATION, "hearing_day_head_latest_ck",
                    "UPDATE hearing_day_head SET latest_share_id = '" + shareId + "' WHERE hearing_id = '"
                            + hearingId + "'");
        }

        private String share(final UUID shareId, final String sharedAt, final String latest,
                final String checksum, final String status, final String reason, final String ljaCode) {
            return "INSERT INTO hearing_share (share_id, hearing_id, hearing_day, shared_at, shared_day_london, "
                    + "shared_day_utc, payload_sha256, lja_code, is_latest, arrived_out_of_order, "
                    + "projection_status, projection_reason, projection_version) VALUES ('" + shareId + "', '"
                    + hearingId + "', DATE '" + HEARING_DAY + "', TIMESTAMPTZ '" + sharedAt + "', DATE '"
                    + HEARING_DAY + "', DATE '" + HEARING_DAY + "', " + checksum + ", " + ljaCode + ", "
                    + latest + ", FALSE, " + status + ", " + reason + ", 1)";
        }
    }

    private List<String> appliedVersions() {
        return jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class)
                .list();
    }

    private void assertAccepted(final String sql) {
        assertThatCode(() -> jdbc.sql(sql).update()).doesNotThrowAnyException();
    }

    private void assertRefused(final String sqlState, final String constraint, final String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).update())
                .satisfies(refusal -> assertThat(sqlStateOf(refusal)).as("SQLSTATE").isEqualTo(sqlState))
                .hasMessageContaining(constraint);
    }

    private static String sqlStateOf(final Throwable refusal) {
        Throwable cause = refusal;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        return cause == null ? null : ((SQLException) cause).getSQLState();
    }

    private static FluentConfiguration flyway(final String schema) {
        return Flyway.configure()
                .dataSource(PostgresTestSupport.container().getJdbcUrl(),
                        PostgresTestSupport.container().getUsername(),
                        PostgresTestSupport.container().getPassword())
                .schemas(schema)
                .createSchemas(true);
    }
}
