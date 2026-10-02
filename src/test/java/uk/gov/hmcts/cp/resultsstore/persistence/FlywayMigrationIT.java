package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Reading;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Share;
import uk.gov.hmcts.cp.resultsstore.domain.ShareIdentity;
import uk.gov.hmcts.cp.resultsstore.domain.SharedDays;
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

    /** SQLSTATE of a FOREIGN KEY violation. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    /** SQLSTATE the schema's guard triggers raise for an update or delete they forbid. */
    private static final String RESTRICT_VIOLATION = "23001";

    /** SQLSTATE PostgreSQL gives for a value written to a GENERATED ALWAYS identity column. */
    private static final String GENERATED_ALWAYS = "428C9";

    private static final String SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final String HEARING_DAY = "2026-10-02";

    private static final String SHARED_AT = "2026-10-02T14:19:50.706Z";

    /** The same instant as {@link #SHARED_AT} spelt with four fraction digits: a different share id. */
    private static final String SHARED_AT_FOUR_DIGITS = "2026-10-02T14:19:50.7060Z";

    /** The longest reason a bounded column holds. */
    private static final String REASON_120 = "R".repeat(120);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

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
            final String messageId = messageId();
            final UUID hearingId = UUID.randomUUID();

            assertAccepted(receipt(messageId, "'RECEIVED'", whole(hearingId), "NULL", "NULL", "NULL", "NULL"));

            assertThat(receiptRow(messageId)).containsAllEntriesOf(Map.of(
                    "status", "RECEIVED", "hearing_id", hearingId.toString(), "hearing_day", HEARING_DAY,
                    "shared_at_matches", true, "settled", false, "attempts", 1, "has_text", false));
        }

        @Test
        void insert_of_a_no_identity_receipt_with_part_of_the_identity_should_be_accepted() {
            final String messageId = messageId();
            final UUID hearingId = UUID.randomUUID();

            assertAccepted(receipt(messageId, "'NO_IDENTITY'", "'" + hearingId + "', NULL, NULL", "NULL",
                    "clock_timestamp()", "'MISSING_HEARING_DAY'", "'{\"hearing\":{}}'"));

            final Map<String, Object> row = receiptRow(messageId);
            assertThat(row).containsAllEntriesOf(Map.of(
                    "status", "NO_IDENTITY", "hearing_id", hearingId.toString(), "settled", true,
                    "reason", "MISSING_HEARING_DAY", "message_text", "{\"hearing\":{}}"));
            assertThat(row.get("hearing_day")).isNull();
            assertThat(row.get("shared_at_matches")).isNull();
        }

        @Test
        void insert_of_a_reason_of_the_longest_length_should_be_accepted() {
            final String messageId = messageId();

            assertAccepted(receipt(messageId, "'UNREADABLE'", "NULL", "NULL", "clock_timestamp()",
                    "'" + REASON_120 + "'", "'x'"));

            assertThat(receiptRow(messageId)).containsEntry("reason", REASON_120);
        }

        @Test
        void insert_of_a_reason_past_its_bound_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_reason_length_ck",
                    receipt("'UNREADABLE'", "NULL", "NULL", "clock_timestamp()", "'" + REASON_120 + "R'", "'x'"));
        }

        @Test
        void insert_of_zero_attempts_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_attempts_ck",
                    withColumn(receipt("'RECEIVED'", whole(), "NULL", "NULL", "NULL", "NULL"), "attempts", "0"));
        }

        @Test
        void insert_of_a_negative_delivery_count_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_delivery_count_ck",
                    withColumn(receipt("'RECEIVED'", whole(), "NULL", "NULL", "NULL", "NULL"), "delivery_count",
                            "-1"));
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
            final String messageId = messageId();
            final String insert = "INSERT INTO event_receipt (message_id, status, hearing_id, hearing_day, "
                    + "shared_at) VALUES ('" + messageId + "', 'RECEIVED', " + whole() + ")";
            assertAccepted(insert);
            assertThat(receiptRow(messageId)).containsEntry("status", "RECEIVED");

            assertRefused(UNIQUE_VIOLATION, "event_receipt_pk", insert);
        }

        @Test
        void insert_of_a_received_receipt_with_a_share_id_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_share_id_ck",
                    receipt("'RECEIVED'", whole(), "'" + UUID.randomUUID() + "'", "NULL", "NULL", "NULL"));
        }

        @Test
        void insert_of_a_no_identity_receipt_with_a_share_id_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "event_receipt_share_id_ck",
                    receipt("'NO_IDENTITY'", "NULL", "'" + UUID.randomUUID() + "'", "clock_timestamp()",
                            "'MISSING_HEARING_ID'", "'{}'"));
        }

        @Test
        void update_of_a_received_receipt_to_stored_should_be_accepted() {
            final String messageId = messageId();
            jdbc.sql(receipt(messageId, "'RECEIVED'", whole(), "NULL", "NULL", "NULL", "NULL")).update();

            assertAccepted("UPDATE event_receipt SET status = 'STORED', share_id = '" + UUID.randomUUID()
                    + "', settled_at = clock_timestamp() WHERE message_id = '" + messageId + "'");

            assertThat(receiptRow(messageId)).containsAllEntriesOf(Map.of("status", "STORED", "settled", true));
        }

        @Test
        void update_of_a_settled_receipt_s_delivery_details_should_be_accepted() {
            final String messageId = messageId();
            jdbc.sql(receipt(messageId, "'UNREADABLE'", "NULL", "NULL", "clock_timestamp()", "'NOT_JSON'",
                    "'x'")).update();

            assertAccepted("UPDATE event_receipt SET attempts = attempts + 1, delivery_count = 2, "
                    + "last_received_at = clock_timestamp() WHERE message_id = '" + messageId + "'");

            assertThat(receiptRow(messageId)).containsAllEntriesOf(Map.of(
                    "status", "UNREADABLE", "attempts", 2, "reason", "NOT_JSON"));
        }

        @ParameterizedTest(name = "SET {0}")
        @ValueSource(strings = {
            "status = 'DUPLICATE'",
            "share_id = gen_random_uuid()",
            "settled_at = clock_timestamp() + INTERVAL '1 second'"
        })
        void update_of_a_stored_receipt_s_end_state_should_be_refused(final String assignment) {
            final String messageId = messageId();
            jdbc.sql(receipt(messageId, "'STORED'", whole(), "'" + UUID.randomUUID() + "'", "clock_timestamp()",
                    "NULL", "NULL")).update();

            assertRefused(RESTRICT_VIOLATION, "event_receipt_settled_guard",
                    "UPDATE event_receipt SET " + assignment + " WHERE message_id = '" + messageId + "'");
        }

        @ParameterizedTest(name = "SET {0}")
        @ValueSource(strings = {
            "status = 'NO_IDENTITY'",
            "reason = 'NOT_OBJECT'",
            "message_text = 'y'"
        })
        void update_of_a_non_share_receipt_s_end_state_should_be_refused(final String assignment) {
            final String messageId = messageId();
            jdbc.sql(receipt(messageId, "'UNREADABLE'", "NULL", "NULL", "clock_timestamp()", "'NOT_JSON'",
                    "'x'")).update();

            assertRefused(RESTRICT_VIOLATION, "event_receipt_settled_guard",
                    "UPDATE event_receipt SET " + assignment + " WHERE message_id = '" + messageId + "'");
        }

        @ParameterizedTest(name = "SET {0}")
        @ValueSource(strings = {
            "message_id = 'ID:other'",
            "hearing_id = gen_random_uuid()",
            "hearing_day = DATE '2026-10-03'",
            "shared_at = shared_at + INTERVAL '1 second'",
            "first_received_at = clock_timestamp()"
        })
        void update_of_a_receipt_s_fixed_columns_should_be_refused(final String assignment) {
            final String messageId = messageId();
            jdbc.sql(receipt(messageId, "'RECEIVED'", whole(), "NULL", "NULL", "NULL", "NULL")).update();

            assertRefused(RESTRICT_VIOLATION, "event_receipt_fixed_columns_guard",
                    "UPDATE event_receipt SET " + assignment + " WHERE message_id = '" + messageId + "'");
        }

        private String receipt(final String status, final String identity, final String shareId,
                final String settledAt, final String reason, final String messageText) {
            return receipt(messageId(), status, identity, shareId, settledAt, reason, messageText);
        }

        private String receipt(final String messageId, final String status, final String identity,
                final String shareId, final String settledAt, final String reason, final String messageText) {
            final String identityValues = "NULL".equals(identity) ? "NULL, NULL, NULL" : identity;
            return "INSERT INTO event_receipt (message_id, status, hearing_id, hearing_day, shared_at, "
                    + "share_id, settled_at, reason, message_text) VALUES ('" + messageId + "', "
                    + status + ", " + identityValues + ", " + shareId + ", " + settledAt + ", " + reason
                    + ", " + messageText + ")";
        }

        private String whole() {
            return whole(UUID.randomUUID());
        }

        private String whole(final UUID hearingId) {
            return "'" + hearingId + "', DATE '" + HEARING_DAY + "', TIMESTAMPTZ '" + SHARED_AT + "'";
        }

        private String messageId() {
            return "ID:" + UUID.randomUUID();
        }

        private Map<String, Object> receiptRow(final String messageId) {
            return jdbc.sql("SELECT status, hearing_id::text AS hearing_id, hearing_day::text AS hearing_day, "
                            + "shared_at = TIMESTAMPTZ '" + SHARED_AT + "' AS shared_at_matches, "
                            + "settled_at IS NOT NULL AS settled, attempts, reason, message_text, "
                            + "message_text IS NOT NULL AS has_text FROM event_receipt WHERE message_id = :m")
                    .param("m", messageId)
                    .query()
                    .singleRow();
        }
    }

    @Nested
    @DisplayName("hearing_share and its children")
    class HearingShare {

        /** When the day's first share, the latest one every test starts from, was shared. */
        private static final String FIRST_SHARED_AT = "2026-10-02T09:00:00.000Z";

        private UUID hearingId;

        private UUID firstShare;

        /**
         * A complete day: a day with shares must name its latest share at commit, so the day row, its
         * first share and the move onto it are written in one transaction, as the store does.
         */
        @BeforeEach
        void aHearingDay() {
            hearingId = UUID.randomUUID();
            firstShare = completeDay(HEARING_DAY, FIRST_SHARED_AT);
        }

        @Test
        void a_new_day_with_its_first_share_as_latest_in_one_transaction_should_be_accepted() {
            final UUID shareId = completeDay("2026-10-03", SHARED_AT);

            assertThat(jdbc.sql("SELECT latest_share_id::text AS latest, share_count FROM hearing_day_head "
                            + "WHERE hearing_id = :h AND hearing_day = DATE '2026-10-03'")
                    .param("h", hearingId)
                    .query()
                    .singleRow()).containsAllEntriesOf(Map.of("latest", shareId.toString(), "share_count", 1));
        }

        @Test
        void insert_of_a_day_row_with_no_shares_should_be_accepted() {
            assertAccepted("INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES ('" + hearingId
                    + "', DATE '2026-10-03')");
        }

        @Test
        void insert_of_a_share_into_a_day_row_with_no_latest_share_should_be_refused() {
            jdbc.sql("INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (:h, DATE '2026-10-03')")
                    .param("h", hearingId)
                    .update();

            assertRefused(CHECK_VIOLATION, "hearing_day_head_has_latest_guard",
                    onDay("2026-10-03", share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'",
                            "NULL", "NULL")));
        }

        @Test
        void insert_of_a_latest_share_without_moving_its_day_row_onto_it_should_be_refused() {
            jdbc.sql("INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (:h, DATE '2026-10-03')")
                    .param("h", hearingId)
                    .update();

            assertRefused(CHECK_VIOLATION, "hearing_day_head_has_latest_guard",
                    onDay("2026-10-03", share(UUID.randomUUID(), SHARED_AT, "TRUE", "'" + SHA256 + "'", "'OK'",
                            "NULL", "NULL")));
        }

        @Test
        void update_emptying_a_day_row_that_has_shares_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_day_head_has_latest_guard",
                    "UPDATE hearing_day_head SET latest_share_id = NULL, share_count = 0 WHERE hearing_id = '"
                            + hearingId + "'");
        }

        @Test
        void insert_of_a_share_with_its_whole_row_should_be_accepted() {
            final UUID shareId = UUID.randomUUID();

            assertAccepted(share(shareId, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "'2577'"));

            final Map<String, Object> row = shareRow(shareId);
            assertThat(row).containsAllEntriesOf(Map.of(
                    "hearing_id", hearingId.toString(), "hearing_day", HEARING_DAY, "shared_at_matches", true,
                    "payload_sha256", SHA256, "lja_code", "2577", "is_latest", false,
                    "projection_status", "OK", "projection_version", 1, "projection_attempts", 1,
                    "has_stored_seq", true));
            assertThat(row.get("projection_reason")).isNull();
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
        void insert_of_the_same_instant_spelt_differently_should_insert_no_row() {
            final UUID first = UUID.randomUUID();
            jdbc.sql(share(first, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL")).update();

            final int inserted = jdbc.sql(share(UUID.randomUUID(), SHARED_AT_FOUR_DIGITS, "FALSE",
                            "'" + SHA256 + "'", "'OK'", "NULL", "NULL")
                    + " ON CONFLICT (hearing_id, hearing_day, shared_at) DO NOTHING").update();

            assertThat(inserted).isZero();
            assertThat(shareIdsOfTheDay()).containsExactly(first.toString());
        }

        @Test
        void insert_of_the_same_instant_spelt_differently_without_on_conflict_should_be_refused() {
            jdbc.sql(share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL"))
                    .update();

            assertRefused(UNIQUE_VIOLATION, "hearing_share_identity_uk",
                    share(UUID.randomUUID(), SHARED_AT_FOUR_DIGITS, "FALSE", "'" + SHA256 + "'", "'OK'",
                            "NULL", "NULL"));
        }

        @Test
        void insert_of_a_latest_share_on_each_day_of_one_hearing_should_be_accepted() {
            final UUID dayTwo = completeDay("2026-10-03", SHARED_AT);

            assertThat(jdbc.sql("SELECT share_id::text FROM hearing_share WHERE hearing_id = :h AND is_latest "
                            + "ORDER BY hearing_day")
                    .param("h", hearingId)
                    .query(String.class)
                    .list()).containsExactly(firstShare.toString(), dayTwo.toString());
        }

        @Test
        void insert_of_a_second_latest_share_for_one_day_should_be_refused() {
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
            final UUID shareId = UUID.randomUUID();

            assertAccepted(share(shareId, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'FAILED'",
                    "'WRONG_TYPE:hearing.isSJPHearing'", "NULL"));

            assertThat(shareRow(shareId)).containsAllEntriesOf(Map.of(
                    "projection_status", "FAILED", "projection_reason", "WRONG_TYPE:hearing.isSJPHearing"));
        }

        @Test
        void insert_of_a_failed_share_with_a_reason_of_the_longest_length_should_be_accepted() {
            final UUID shareId = UUID.randomUUID();

            assertAccepted(share(shareId, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'FAILED'",
                    "'" + REASON_120 + "'", "NULL"));

            assertThat(shareRow(shareId)).containsEntry("projection_reason", REASON_120);
        }

        @Test
        void insert_of_a_failed_share_with_a_reason_past_its_bound_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_share_projection_reason_length_ck",
                    share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'FAILED'",
                            "'" + REASON_120 + "R'", "NULL"));
        }

        @Test
        void insert_of_projection_version_zero_should_be_refused() {
            final String insert = share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'",
                    "NULL", "NULL");

            assertRefused(CHECK_VIOLATION, "hearing_share_projection_version_ck",
                    insert.substring(0, insert.length() - ", 1)".length()) + ", 0)");
        }

        @Test
        void insert_of_zero_projection_attempts_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_share_projection_attempts_ck",
                    withColumn(share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL",
                            "NULL"), "projection_attempts", "0"));
        }

        @Test
        void insert_of_a_share_that_is_its_own_predecessor_should_be_refused() {
            final UUID shareId = UUID.randomUUID();

            assertRefused(CHECK_VIOLATION, "hearing_share_not_own_predecessor_ck",
                    withColumn(share(shareId, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL"),
                            "predecessor_share_id", "'" + shareId + "'"));
        }

        @Test
        void insert_of_a_share_whose_predecessor_is_unknown_should_be_refused() {
            assertRefused(FOREIGN_KEY_VIOLATION, "hearing_share_predecessor_fk",
                    withColumn(share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL",
                            "NULL"), "predecessor_share_id", "'" + UUID.randomUUID() + "'"));
        }

        @Test
        void insert_of_a_share_for_a_day_with_no_day_row_should_be_refused() {
            assertRefused(FOREIGN_KEY_VIOLATION, "hearing_share_day_fk",
                    share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL")
                            .replace("'" + hearingId + "'", "'" + UUID.randomUUID() + "'"));
        }

        @Test
        void insert_of_a_payload_for_an_unknown_share_should_be_refused() {
            assertRefused(FOREIGN_KEY_VIOLATION, "hearing_share_payload_share_fk",
                    "INSERT INTO hearing_share_payload (share_id, payload_text, text_bytes) VALUES ('"
                            + UUID.randomUUID() + "', '{}', 2)");
        }

        @Test
        void insert_of_a_defendant_for_an_unknown_share_should_be_refused() {
            assertRefused(FOREIGN_KEY_VIOLATION, "share_defendant_share_fk",
                    "INSERT INTO share_defendant (share_id, case_id, defendant_id) VALUES ('" + UUID.randomUUID()
                            + "', 'c1c1c1c1-0000-4000-8000-000000000001', 'd1d1d1d1-0000-4000-8000-000000000001')");
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

            final Map<String, Object> row = jdbc.sql("SELECT payload_text, text_bytes, payload_json IS NULL AS "
                            + "no_parsed_copy FROM hearing_share_payload WHERE share_id = :s")
                    .param("s", shareId)
                    .query()
                    .singleRow();
            assertThat(row).containsAllEntriesOf(Map.of(
                    "payload_text", "{\"a\":\"\\u0000\"}", "text_bytes", 14, "no_parsed_copy", true));
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

            assertThat(jdbc.sql("SELECT case_id::text FROM share_defendant WHERE share_id = :s ORDER BY case_id")
                    .param("s", shareId)
                    .query(String.class)
                    .list()).containsExactly("c1c1c1c1-0000-4000-8000-000000000001",
                            "c2c2c2c2-0000-4000-8000-000000000002");
        }

        @Test
        void update_of_a_day_row_naming_a_latest_share_to_a_zero_count_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_day_head_latest_ck",
                    "UPDATE hearing_day_head SET share_count = 0 WHERE hearing_id = '" + hearingId + "'");
        }

        @Test
        void update_of_a_day_row_to_a_positive_count_with_no_latest_share_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_day_head_latest_ck",
                    "UPDATE hearing_day_head SET latest_share_id = NULL WHERE hearing_id = '" + hearingId + "'");
        }

        @Test
        void update_of_a_day_row_to_a_negative_count_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_day_head_count_ck",
                    "UPDATE hearing_day_head SET share_count = -1 WHERE hearing_id = '" + hearingId + "'");
        }

        @Test
        void update_of_a_day_row_to_an_unknown_latest_share_should_be_refused() {
            assertRefused(FOREIGN_KEY_VIOLATION, "hearing_day_head_latest_fk",
                    "UPDATE hearing_day_head SET latest_share_id = '" + UUID.randomUUID() + "', share_count = 1 "
                            + "WHERE hearing_id = '" + hearingId + "'");
        }

        @Test
        void insert_of_a_second_day_row_for_one_hearing_day_should_be_refused() {
            assertRefused(UNIQUE_VIOLATION, "hearing_day_head_pk",
                    "INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES ('" + hearingId + "', DATE '"
                            + HEARING_DAY + "')");
        }

        @Test
        void insert_of_a_second_share_with_one_share_id_should_be_refused() {
            final UUID shareId = UUID.randomUUID();
            jdbc.sql(share(shareId, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL", "NULL")).update();

            assertRefused(UNIQUE_VIOLATION, "hearing_share_pk",
                    share(shareId, "2026-10-02T15:00:00.000Z", "FALSE", "'" + SHA256 + "'", "'OK'", "NULL",
                            "NULL"));
        }

        @Test
        void insert_of_a_second_payload_for_one_share_should_be_refused() {
            final UUID shareId = storedShare(SHARED_AT, "FALSE");
            jdbc.sql(payload(shareId)).update();

            assertRefused(UNIQUE_VIOLATION, "hearing_share_payload_pk", payload(shareId));
        }

        @Test
        void insert_of_two_shares_should_give_a_rising_stored_seq_and_a_database_clock_stored_at() {
            final Instant before = databaseNow();
            final UUID first = storedShare(SHARED_AT, "FALSE");
            final UUID second = storedShare("2026-10-02T15:00:00.000Z", "FALSE");
            final Instant after = databaseNow();

            final Map<String, Object> firstRow = clocks(first);
            final Map<String, Object> secondRow = clocks(second);
            assertThat((Long) secondRow.get("stored_seq")).isGreaterThan((Long) firstRow.get("stored_seq"));
            assertThat(List.of(firstRow.get("stored_at"), secondRow.get("stored_at")))
                    .allSatisfy(storedAt -> assertThat(((OffsetDateTime) storedAt).toInstant())
                            .isBetween(before, after));
        }

        @ParameterizedTest(name = "SET {0}")
        @ValueSource(strings = {
            "share_id = gen_random_uuid()",
            "hearing_day = DATE '2026-10-03'",
            "shared_at = shared_at + INTERVAL '1 second'",
            "shared_day_london = DATE '2026-10-03'",
            "shared_day_utc = DATE '2026-10-03'",
            "stored_at = clock_timestamp()",
            "payload_sha256 = repeat('a', 64)",
            "arrived_out_of_order = TRUE",
            "enrichment_applied = TRUE"
        })
        void update_of_a_share_s_fixed_columns_should_be_refused(final String assignment) {
            final UUID shareId = storedShare(SHARED_AT, "FALSE");

            assertRefused(RESTRICT_VIOLATION, "hearing_share_fixed_columns_guard",
                    "UPDATE hearing_share SET " + assignment + " WHERE share_id = '" + shareId + "'");
        }

        @Test
        void update_of_an_ok_share_s_predecessor_and_day_youth_flag_should_be_accepted() {
            final UUID earlier = storedShare(SHARED_AT, "FALSE");
            final UUID shareId = storedShare("2026-10-02T15:00:00.000Z", "FALSE");

            assertAccepted("UPDATE hearing_share SET predecessor_share_id = '" + earlier
                    + "', day_youth_seen = TRUE WHERE share_id = '" + shareId + "'");

            assertThat(jdbc.sql("SELECT predecessor_share_id::text FROM hearing_share WHERE share_id = :s "
                            + "AND day_youth_seen")
                    .param("s", shareId)
                    .query(String.class)
                    .single()).isEqualTo(earlier.toString());
        }

        /** The sweep re-extracts FAILED rows only (FR-033): an OK share's key details and projection are final. */
        @ParameterizedTest(name = "SET {0}")
        @ValueSource(strings = {
            "lja_code = '9999'",
            "any_subject_is_youth = TRUE",
            "projection_version = 2",
            "projection_attempts = 2",
            "projected_at = clock_timestamp()",
            "projection_status = 'FAILED', projection_reason = 'WRONG_TYPE:hearing.isSJPHearing'"
        })
        void update_of_an_ok_share_s_key_details_or_projection_should_be_refused(final String assignment) {
            final UUID shareId = storedShare(SHARED_AT, "FALSE");

            assertRefused(RESTRICT_VIOLATION, "hearing_share_projection_guard",
                    "UPDATE hearing_share SET " + assignment + " WHERE share_id = '" + shareId + "'");
        }

        @Test
        void update_of_a_failed_share_to_its_extracted_key_details_should_be_accepted() {
            final UUID shareId = UUID.randomUUID();
            jdbc.sql(share(shareId, SHARED_AT, "FALSE", "'" + SHA256 + "'", "'FAILED'",
                    "'WRONG_TYPE:hearing.isSJPHearing'", "NULL")).update();

            assertAccepted("UPDATE hearing_share SET lja_code = '2577', projection_status = 'OK', "
                    + "projection_reason = NULL, projection_version = 2, projection_attempts = 2, "
                    + "projected_at = clock_timestamp() WHERE share_id = '" + shareId + "'");

            assertThat(shareRow(shareId)).containsAllEntriesOf(Map.of(
                    "lja_code", "2577", "projection_status", "OK", "projection_version", 2));
        }

        @Test
        void delete_of_a_share_should_be_refused() {
            final UUID shareId = storedShare(SHARED_AT, "FALSE");

            assertRefused(RESTRICT_VIOLATION, "hearing_share_delete_guard",
                    "DELETE FROM hearing_share WHERE share_id = '" + shareId + "'");
        }

        @Test
        void update_of_a_payload_should_be_refused() {
            final UUID shareId = storedShare(SHARED_AT, "FALSE");
            jdbc.sql(payload(shareId)).update();

            assertRefused(RESTRICT_VIOLATION, "hearing_share_payload_update_guard",
                    "UPDATE hearing_share_payload SET payload_json = NULL WHERE share_id = '" + shareId + "'");
        }

        @Test
        void delete_of_a_payload_should_be_refused() {
            final UUID shareId = storedShare(SHARED_AT, "FALSE");
            jdbc.sql(payload(shareId)).update();

            assertRefused(RESTRICT_VIOLATION, "hearing_share_payload_delete_guard",
                    "DELETE FROM hearing_share_payload WHERE share_id = '" + shareId + "'");
        }

        /**
         * The sweep replaces a share's defendant rows when it re-extracts; their immutability is the
         * application's, under the hearing-day lock, not a database guard.
         */
        @Test
        void update_of_a_defendant_row_should_be_accepted() {
            final UUID shareId = storedShare(SHARED_AT, "FALSE");
            jdbc.sql(defendant(shareId)).update();

            assertAccepted("UPDATE share_defendant SET master_defendant_id = 'e1e1e1e1-0000-4000-8000-000000000001' "
                    + "WHERE share_id = '" + shareId + "'");
        }

        @Test
        void delete_of_a_defendant_row_should_be_accepted() {
            final UUID shareId = storedShare(SHARED_AT, "FALSE");
            jdbc.sql(defendant(shareId)).update();

            assertAccepted("DELETE FROM share_defendant WHERE share_id = '" + shareId + "'");
        }

        @ParameterizedTest(name = "SET {0}")
        @ValueSource(strings = {
            "hearing_id = gen_random_uuid()",
            "hearing_day = DATE '2026-10-03'",
            "first_stored_at = clock_timestamp()"
        })
        void update_of_a_day_row_s_fixed_columns_should_be_refused(final String assignment) {
            assertRefused(RESTRICT_VIOLATION, "hearing_day_head_fixed_columns_guard",
                    "UPDATE hearing_day_head SET " + assignment + " WHERE hearing_id = '" + hearingId + "'");
        }

        @Test
        void delete_of_a_day_row_should_be_refused() {
            assertRefused(RESTRICT_VIOLATION, "hearing_day_head_delete_guard",
                    "DELETE FROM hearing_day_head WHERE hearing_id = '" + hearingId + "'");
        }

        @Test
        void update_of_a_day_row_to_a_share_that_is_not_latest_should_be_refused() {
            final UUID shareId = storedShare(SHARED_AT, "FALSE");

            assertRefused(CHECK_VIOLATION, "hearing_day_head_latest_is_latest_guard",
                    "UPDATE hearing_day_head SET latest_share_id = '" + shareId + "', share_count = 1 "
                            + "WHERE hearing_id = '" + hearingId + "'");
        }

        @Test
        void update_of_a_day_row_to_the_latest_share_of_another_day_should_be_refused() {
            final UUID otherDay = completeDay("2026-10-03", SHARED_AT);

            assertRefused(FOREIGN_KEY_VIOLATION, "hearing_day_head_latest_fk",
                    "UPDATE hearing_day_head SET latest_share_id = '" + otherDay + "', share_count = 1 "
                            + "WHERE hearing_id = '" + hearingId + "' AND hearing_day = DATE '" + HEARING_DAY + "'");
        }

        @Test
        void update_clearing_the_latest_flag_of_the_share_the_day_row_names_should_be_refused() {
            assertRefused(CHECK_VIOLATION, "hearing_day_head_latest_is_latest_guard",
                    "UPDATE hearing_share SET is_latest = FALSE WHERE share_id = '" + firstShare + "'");
        }

        @Test
        void a_newer_share_taking_over_as_latest_in_one_transaction_should_be_accepted() {
            final UUID older = firstShare;
            final UUID newer = storedShare("2026-10-02T15:00:00.000Z", "FALSE");

            transaction.executeWithoutResult(status -> {
                jdbc.sql("UPDATE hearing_share SET is_latest = FALSE WHERE share_id = '" + older + "'").update();
                jdbc.sql("UPDATE hearing_share SET is_latest = TRUE, predecessor_share_id = '" + older
                        + "' WHERE share_id = '" + newer + "'").update();
                jdbc.sql("UPDATE hearing_day_head SET latest_share_id = '" + newer + "', share_count = 2 "
                        + "WHERE hearing_id = '" + hearingId + "'").update();
            });

            assertThat(latestOfTheDay()).isEqualTo(newer.toString());
        }

        @Test
        void insert_of_a_share_whose_predecessor_is_on_another_day_should_be_refused() {
            final UUID otherDay = completeDay("2026-10-01", "2026-10-01T14:19:50.706Z");

            assertRefused(FOREIGN_KEY_VIOLATION, "hearing_share_predecessor_fk",
                    withColumn(share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL",
                            "NULL"), "predecessor_share_id", "'" + otherDay + "'"));
        }

        @Test
        void insert_of_a_share_whose_predecessor_was_shared_later_should_be_refused() {
            final UUID later = storedShare("2026-10-02T15:00:00.000Z", "FALSE");

            assertRefused(CHECK_VIOLATION, "hearing_share_predecessor_earlier_guard",
                    withColumn(share(UUID.randomUUID(), SHARED_AT, "FALSE", "'" + SHA256 + "'", "'OK'", "NULL",
                            "NULL"), "predecessor_share_id", "'" + later + "'"));
        }

        @Test
        void update_closing_a_two_share_cycle_should_be_refused() {
            final UUID first = storedShare(SHARED_AT, "FALSE");
            final UUID second = UUID.randomUUID();
            jdbc.sql(withColumn(share(second, "2026-10-02T15:00:00.000Z", "FALSE", "'" + SHA256 + "'", "'OK'",
                    "NULL", "NULL"), "predecessor_share_id", "'" + first + "'")).update();

            assertRefused(CHECK_VIOLATION, "hearing_share_predecessor_earlier_guard",
                    "UPDATE hearing_share SET predecessor_share_id = '" + second + "' WHERE share_id = '" + first
                            + "'");
        }

        @Test
        void insert_of_a_share_whose_predecessor_is_an_earlier_share_of_the_day_should_be_accepted() {
            final UUID earlier = storedShare(SHARED_AT, "FALSE");
            final UUID shareId = UUID.randomUUID();

            assertAccepted(withColumn(share(shareId, "2026-10-02T15:00:00.000Z", "FALSE", "'" + SHA256 + "'",
                    "'OK'", "NULL", "NULL"), "predecessor_share_id", "'" + earlier + "'"));

            assertThat(jdbc.sql("SELECT predecessor_share_id::text FROM hearing_share WHERE share_id = :s")
                    .param("s", shareId)
                    .query(String.class)
                    .single()).isEqualTo(earlier.toString());
        }

        private UUID storedShare(final String sharedAt, final String latest) {
            final UUID shareId = UUID.randomUUID();
            jdbc.sql(share(shareId, sharedAt, latest, "'" + SHA256 + "'", "'OK'", "NULL", "NULL")).update();
            return shareId;
        }

        private String payload(final UUID shareId) {
            return "INSERT INTO hearing_share_payload (share_id, payload_text, text_bytes, payload_json) VALUES ('"
                    + shareId + "', '{}', 2, '{}'::jsonb)";
        }

        private String defendant(final UUID shareId) {
            return "INSERT INTO share_defendant (share_id, case_id, defendant_id) VALUES ('" + shareId
                    + "', 'c1c1c1c1-0000-4000-8000-000000000001', 'd1d1d1d1-0000-4000-8000-000000000001')";
        }

        private String latestOfTheDay() {
            return jdbc.sql("SELECT latest_share_id::text FROM hearing_day_head WHERE hearing_id = :h "
                            + "AND hearing_day = DATE '" + HEARING_DAY + "'")
                    .param("h", hearingId)
                    .query(String.class)
                    .single();
        }

        private Map<String, Object> clocks(final UUID shareId) {
            return jdbc.sql("SELECT stored_seq, stored_at FROM hearing_share WHERE share_id = :s")
                    .param("s", shareId)
                    .query((rs, rowNum) -> Map.<String, Object>of("stored_seq", rs.getLong(1),
                            "stored_at", rs.getObject(2, OffsetDateTime.class)))
                    .single();
        }

        private Instant databaseNow() {
            return jdbc.sql("SELECT clock_timestamp()")
                    .query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                    .single();
        }

        /** The day's first share, latest, with its day row moved onto it, in one transaction. */
        private UUID completeDay(final String hearingDay, final String sharedAt) {
            final UUID shareId = UUID.randomUUID();
            transaction.executeWithoutResult(status -> {
                assertAccepted("INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES ('" + hearingId
                        + "', DATE '" + hearingDay + "')");
                assertAccepted(onDay(hearingDay, share(shareId, sharedAt, "TRUE", "'" + SHA256 + "'", "'OK'",
                        "NULL", "NULL")));
                assertAccepted("UPDATE hearing_day_head SET latest_share_id = '" + shareId + "', share_count = 1 "
                        + "WHERE hearing_id = '" + hearingId + "' AND hearing_day = DATE '" + hearingDay + "'");
            });
            return shareId;
        }

        /** A share insert moved from the test's day onto another. */
        private String onDay(final String hearingDay, final String insert) {
            return insert.replace("DATE '" + HEARING_DAY + "', TIMESTAMPTZ", "DATE '" + hearingDay + "', TIMESTAMPTZ");
        }

        /** The shares of the test's day stored at {@link #SHARED_AT}, however it was spelt. */
        private List<String> shareIdsOfTheDay() {
            return jdbc.sql("SELECT share_id::text FROM hearing_share WHERE hearing_id = :h "
                            + "AND shared_at = TIMESTAMPTZ '" + SHARED_AT + "'")
                    .param("h", hearingId)
                    .query(String.class)
                    .list();
        }

        private Map<String, Object> shareRow(final UUID shareId) {
            return jdbc.sql("SELECT hearing_id::text AS hearing_id, hearing_day::text AS hearing_day, "
                            + "shared_at = TIMESTAMPTZ '" + SHARED_AT + "' AS shared_at_matches, payload_sha256, "
                            + "lja_code, is_latest, projection_status, projection_reason, projection_version, "
                            + "projection_attempts, stored_seq IS NOT NULL AS has_stored_seq "
                            + "FROM hearing_share WHERE share_id = :s")
                    .param("s", shareId)
                    .query()
                    .singleRow();
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

    @Nested
    @DisplayName("identity values at the edges of the four-digit years")
    class IdentityEdges {

        private final ShareIdentityParser parser = new ShareIdentityParser(JsonMapper.builder().build());

        /**
         * Every identity the parser accepts must be storable: the values go through the JDBC binding
         * the store uses (java.time types) and come back as the same day count and epoch instant.
         */
        @ParameterizedTest(name = "hearingDay = {0}, sharedTime = {1}")
        @CsvSource(delimiter = '|', value = {
            "0000-01-01 | 0000-01-01T00:00:00+18:00",
            "0000-12-31 | 0001-01-01T00:30:00+01:00",
            "0001-01-01 | 0001-01-01T00:00:00Z",
            "9999-12-31 | 9999-12-31T23:30:00-01:00",
            "9999-12-31 | 9999-12-31T23:59:59.999999-18:00"
        })
        void a_parsed_identity_should_be_stored_and_read_back_unchanged(final String hearingDay,
                final String sharedTime) {
            final Reading reading = parser.read("{\"hearing\": {\"id\": \"" + UUID.randomUUID()
                    + "\"}, \"hearingDay\": \"" + hearingDay + "\", \"sharedTime\": \"" + sharedTime + "\"}");
            assertThat(reading).as("parser reading").isInstanceOf(Share.class);
            final ShareIdentity identity = ((Share) reading).identity();
            final SharedDays days = SharedDays.from(identity.sharedAt());

            assertThat(storeAsTheDaysFirstShare(identity, days)).as("rows written").isOne();

            final Map<String, Object> row = jdbc.sql("SELECT hearing_day - DATE '1970-01-01' AS day, "
                            + "shared_day_london - DATE '1970-01-01' AS london, "
                            + "shared_day_utc - DATE '1970-01-01' AS utc, "
                            + "(extract(epoch FROM shared_at) * 1000000)::bigint AS micros "
                            + "FROM hearing_share WHERE share_id = :s")
                    .param("s", identity.shareId())
                    .query()
                    .singleRow();
            assertThat(row).containsAllEntriesOf(Map.of(
                    "day", Math.toIntExact(identity.hearingDay().toEpochDay()),
                    "london", Math.toIntExact(days.london().toEpochDay()),
                    "utc", Math.toIntExact(days.utc().toEpochDay()),
                    "micros", ChronoUnit.MICROS.between(Instant.EPOCH, identity.sharedAt())));
            assertThat(jdbc.sql("SELECT shared_at FROM hearing_share WHERE share_id = :s")
                    .param("s", identity.shareId())
                    .query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                    .single()).as("shared_at read back").isEqualTo(identity.sharedAt());
        }

        /**
         * timestamptz keeps microseconds; the parser truncates a finer shared time to the microsecond
         * before it becomes shared_at, so what is stored is what was parsed (research R8).
         */
        @ParameterizedTest(name = "sharedTime = {0}")
        @CsvSource(delimiter = '|', value = {
            "2026-10-02T14:19:50.1234567Z   | 2026-10-02T14:19:50.123456Z",
            "2026-10-02T14:19:50.12345678Z  | 2026-10-02T14:19:50.123456Z",
            "2026-10-02T14:19:50.123456789Z | 2026-10-02T14:19:50.123456Z",
            "2026-10-02T23:59:59.9999999Z   | 2026-10-02T23:59:59.999999Z"
        })
        void a_shared_time_finer_than_microseconds_should_round_trip_as_its_truncated_microsecond(
                final String sharedTime, final String stored) {
            final Reading reading = parser.read("{\"hearing\": {\"id\": \"" + UUID.randomUUID()
                    + "\"}, \"hearingDay\": \"" + HEARING_DAY + "\", \"sharedTime\": \"" + sharedTime + "\"}");
            assertThat(reading).as("parser reading").isInstanceOf(Share.class);
            final ShareIdentity identity = ((Share) reading).identity();
            final SharedDays days = SharedDays.from(identity.sharedAt());

            storeAsTheDaysFirstShare(identity, days);

            assertThat(jdbc.sql("SELECT shared_at FROM hearing_share WHERE share_id = :s")
                    .param("s", identity.shareId())
                    .query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                    .single()).as("shared_at read back").isEqualTo(identity.sharedAt())
                    .isEqualTo(Instant.parse(stored));
        }

        /**
         * The share as its day's first and latest, bound with the store's java.time types, the day row
         * moved onto it in the same transaction.
         *
         * @return the share rows written
         */
        private int storeAsTheDaysFirstShare(final ShareIdentity identity, final SharedDays days) {
            final Integer written = transaction.execute(status -> {
                jdbc.sql("INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (:h, :d)")
                        .param("h", identity.hearingId())
                        .param("d", identity.hearingDay())
                        .update();
                final int rows = jdbc.sql("INSERT INTO hearing_share (share_id, hearing_id, hearing_day, shared_at, "
                                + "shared_day_london, shared_day_utc, payload_sha256, is_latest, arrived_out_of_order, "
                                + "projection_status, projection_version) VALUES (:s, :h, :d, :at, :london, :utc, '"
                                + SHA256 + "', TRUE, FALSE, 'OK', 1)")
                        .param("s", identity.shareId())
                        .param("h", identity.hearingId())
                        .param("d", identity.hearingDay())
                        .param("at", identity.sharedAt().atOffset(ZoneOffset.UTC))
                        .param("london", days.london())
                        .param("utc", days.utc())
                        .update();
                jdbc.sql("UPDATE hearing_day_head SET latest_share_id = :s, share_count = 1 "
                                + "WHERE hearing_id = :h AND hearing_day = :d")
                        .param("s", identity.shareId())
                        .param("h", identity.hearingId())
                        .param("d", identity.hearingDay())
                        .update();
                return rows;
            });
            return written == null ? 0 : written;
        }
    }

    private List<String> appliedVersions() {
        return jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class)
                .list();
    }

    private void assertAccepted(final String sql) {
        final AtomicInteger written = new AtomicInteger();
        assertThatCode(() -> written.set(jdbc.sql(sql).update())).as("accepted: %s", sql)
                .doesNotThrowAnyException();
        assertThat(written.get()).as("rows written").isOne();
    }

    /** The insert with one more column and its value put first. */
    private static String withColumn(final String insert, final String column, final String value) {
        return insert.replaceFirst("\\(", "(" + column + ", ").replace("VALUES (", "VALUES (" + value + ", ");
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
