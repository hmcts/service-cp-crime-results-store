package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.Arrival;
import uk.gov.hmcts.cp.resultsstore.application.ReceiptState;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.ReceiptStatus;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

/**
 * The receipt rules on PostgreSQL (FR-002 to FR-005, FR-008, FR-009): one row per message id, the
 * attempt count raised on every delivery, the end state written once and kept.
 *
 * <p>Each test works on its own random hearing and message ids, so the suites sharing the container
 * never see each other's rows.
 */
@SpringBootTest
@ActiveProfiles("test")
class JdbcReceiptStoreIT {

    private static final String HEARING_DAY = "2026-10-02";

    private static final String SHARED_TIME = "2026-10-02T14:19:50.706Z";

    private final ShareIdentityParser parser = new ShareIdentityParser(JsonMapper.builder().build());

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private JdbcReceiptStore store;

    private UUID hearingId;

    private String messageId;

    @DynamicPropertySource
    static void database(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @BeforeEach
    void freshIds() {
        store = new JdbcReceiptStore(jdbc, new TransactionTemplate(transactionManager));
        hearingId = UUID.randomUUID();
        messageId = "ID:" + UUID.randomUUID();
    }

    @Nested
    @DisplayName("a share")
    class AShare {

        @Test
        void first_arrival_should_be_received_with_one_attempt() {
            final ReceiptState state = store.recordArrival(arrival(messageId, 1, share()));

            assertThat(state).isEqualTo(new ReceiptState(messageId, ReceiptStatus.RECEIVED, null, 1, true));
            final Map<String, Object> row = row(messageId);
            assertThat(row)
                    .containsEntry("status", "RECEIVED")
                    .containsEntry("hearing_id", hearingId)
                    .containsEntry("hearing_day", Date.valueOf(HEARING_DAY))
                    .containsEntry("attempts", 1)
                    .containsEntry("delivery_count", 1)
                    .containsEntry("settled_at", null)
                    .containsEntry("reason", null)
                    .containsEntry("message_text", null)
                    .containsEntry("share_id", null);
            assertThat(instant(row, "last_received_at")).isAfterOrEqualTo(instant(row, "first_received_at"));
            assertThat(sharedAt(messageId)).isEqualTo(Instant.parse(SHARED_TIME));
        }

        @Test
        void redelivery_should_raise_attempts_and_keep_the_status() {
            store.recordArrival(arrival(messageId, 1, share()));
            final Map<String, Object> first = row(messageId);

            final ReceiptState state = store.recordArrival(arrival(messageId, 4, share()));

            assertThat(state).isEqualTo(new ReceiptState(messageId, ReceiptStatus.RECEIVED, null, 2, false));
            final Map<String, Object> second = row(messageId);
            assertThat(second)
                    .containsEntry("status", "RECEIVED")
                    .containsEntry("attempts", 2)
                    .containsEntry("delivery_count", 4)
                    .containsEntry("settled_at", null)
                    .containsEntry("first_received_at", first.get("first_received_at"));
            assertThat(instant(second, "last_received_at")).isAfter(instant(first, "last_received_at"));
        }

        @Test
        void mark_stored_from_received_should_settle_the_receipt_with_its_share() {
            store.recordArrival(arrival(messageId, 1, share()));
            final UUID shareId = UUID.randomUUID();

            assertThat(store.markStored(messageId, shareId)).isTrue();

            final Map<String, Object> row = row(messageId);
            assertThat(row).containsEntry("status", "STORED").containsEntry("share_id", shareId);
            assertThat(row.get("settled_at")).isNotNull();
        }

        @Test
        void mark_duplicate_from_received_should_settle_the_receipt_with_the_existing_share() {
            store.recordArrival(arrival(messageId, 1, share()));
            final UUID existing = UUID.randomUUID();

            assertThat(store.markDuplicate(messageId, existing)).isTrue();

            final Map<String, Object> row = row(messageId);
            assertThat(row).containsEntry("status", "DUPLICATE").containsEntry("share_id", existing);
            assertThat(row.get("settled_at")).isNotNull();
        }

        @Test
        void mark_on_a_settled_receipt_should_change_nothing() {
            store.recordArrival(arrival(messageId, 1, share()));
            final UUID shareId = UUID.randomUUID();
            store.markStored(messageId, shareId);
            final Map<String, Object> settled = row(messageId);

            assertThat(store.markDuplicate(messageId, UUID.randomUUID())).isFalse();
            assertThat(store.markStored(messageId, UUID.randomUUID())).isFalse();

            assertThat(row(messageId)).isEqualTo(settled);
        }

        @Test
        void mark_on_an_unknown_receipt_should_change_nothing() {
            assertThat(store.markStored(messageId, UUID.randomUUID())).isFalse();
            assertThat(store.markDuplicate(messageId, UUID.randomUUID())).isFalse();

            assertThat(count(messageId)).isZero();
        }

        @Test
        void arrival_after_the_receipt_settled_should_raise_attempts_only() {
            store.recordArrival(arrival(messageId, 1, share()));
            final UUID shareId = UUID.randomUUID();
            store.markStored(messageId, shareId);
            final Map<String, Object> settled = row(messageId);

            final ReceiptState state = store.recordArrival(arrival(messageId, 2, share()));

            assertThat(state).isEqualTo(new ReceiptState(messageId, ReceiptStatus.STORED, shareId, 2, false));
            assertThat(state.isSettled()).isTrue();
            final Map<String, Object> row = row(messageId);
            assertThat(row)
                    .containsEntry("status", "STORED")
                    .containsEntry("share_id", shareId)
                    .containsEntry("settled_at", settled.get("settled_at"))
                    .containsEntry("attempts", 2)
                    .containsEntry("delivery_count", 2);
        }
    }

    @Nested
    @DisplayName("a non-share")
    class ANonShare {

        @Test
        void unreadable_body_should_go_straight_to_its_end_state_with_reason_and_text() {
            final ReceiptState state = store.recordArrival(arrival(messageId, 1, "not json"));

            assertThat(state).isEqualTo(new ReceiptState(messageId, ReceiptStatus.UNREADABLE, null, 1, true));
            final Map<String, Object> row = row(messageId);
            assertThat(row)
                    .containsEntry("status", "UNREADABLE")
                    .containsEntry("reason", "NOT_JSON")
                    .containsEntry("message_text", "not json")
                    .containsEntry("hearing_id", null)
                    .containsEntry("hearing_day", null)
                    .containsEntry("shared_at", null);
            assertThat(row.get("settled_at")).isNotNull();
        }

        @Test
        void body_with_part_of_its_identity_should_be_no_identity_keeping_the_parts_read() {
            final String text = """
                    {"hearing": {"id": "%s"}, "hearingDay": "%s"}""".formatted(hearingId, HEARING_DAY);

            final ReceiptState state = store.recordArrival(arrival(messageId, 1, text));

            assertThat(state.status()).isEqualTo(ReceiptStatus.NO_IDENTITY);
            assertThat(row(messageId))
                    .containsEntry("status", "NO_IDENTITY")
                    .containsEntry("reason", "MISSING_SHARED_TIME")
                    .containsEntry("message_text", text)
                    .containsEntry("hearing_id", hearingId)
                    .containsEntry("hearing_day", Date.valueOf(HEARING_DAY))
                    .containsEntry("shared_at", null);
        }

        @Test
        void text_with_a_raw_nul_should_be_unreadable_without_its_text() {
            final ReceiptState state = store.recordArrival(arrival(messageId, 1, "{\"a\":\"\u0000\"}"));

            assertThat(state.status()).isEqualTo(ReceiptStatus.UNREADABLE);
            assertThat(row(messageId))
                    .containsEntry("reason", "NUL_CHARACTER")
                    .containsEntry("message_text", null);
        }

        @Test
        void message_that_is_not_text_should_be_unreadable_as_not_a_text_message() {
            final ReceiptState state = store.recordArrival(new Arrival(messageId, 1, null,
                    ShareIdentityParser.NotShare.because(
                            NonShareReason.NOT_TEXT_MESSAGE)));

            assertThat(state.status()).isEqualTo(ReceiptStatus.UNREADABLE);
            assertThat(row(messageId))
                    .containsEntry("reason", "NOT_TEXT_MESSAGE")
                    .containsEntry("message_text", null);
        }

        @Test
        void redelivery_should_raise_attempts_and_never_change_the_end_state() {
            store.recordArrival(arrival(messageId, 1, "not json"));
            final Map<String, Object> settled = row(messageId);

            final ReceiptState state = store.recordArrival(arrival(messageId, 3, "not json"));

            assertThat(state).isEqualTo(new ReceiptState(messageId, ReceiptStatus.UNREADABLE, null, 2, false));
            assertThat(row(messageId))
                    .containsEntry("status", "UNREADABLE")
                    .containsEntry("reason", "NOT_JSON")
                    .containsEntry("message_text", "not json")
                    .containsEntry("settled_at", settled.get("settled_at"))
                    .containsEntry("attempts", 2)
                    .containsEntry("delivery_count", 3);
        }
    }

    @Nested
    @DisplayName("no message id")
    class NoMessageId {

        @Test
        void message_without_an_id_should_be_keyed_by_the_checksum_of_its_text() {
            final String text = share();

            final ReceiptState state = store.recordArrival(arrival(null, 1, text));

            final String key = "sha256:" + PayloadChecksum.sha256Hex(text);
            assertThat(state.messageId()).isEqualTo(key);
            assertThat(row(key)).containsEntry("status", "RECEIVED").containsEntry("hearing_id", hearingId);
        }

        @Test
        void same_text_again_without_an_id_should_be_a_further_attempt_on_one_receipt() {
            final String text = share();
            store.recordArrival(arrival(null, 1, text));

            final ReceiptState state = store.recordArrival(arrival(null, 1, text));

            assertThat(state.attempts()).isEqualTo(2);
            assertThat(state.inserted()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void message_with_no_text_and_no_id_should_be_keyed_by_the_checksum_of_nothing(final boolean textMessage) {
            final Arrival arrival = textMessage
                    ? arrival(null, 1, null)
                    : new Arrival(null, 1, null, ShareIdentityParser.NotShare.because(
                            NonShareReason.NOT_TEXT_MESSAGE));
            // Several tests share this key, so the row may already exist: assert the key only.
            final ReceiptState state = store.recordArrival(arrival);

            assertThat(state.messageId()).isEqualTo("sha256:" + PayloadChecksum.sha256Hex(""));
            assertThat(state.status()).isEqualTo(ReceiptStatus.UNREADABLE);
        }
    }

    @Nested
    @DisplayName("a failure")
    class AFailure {

        @Test
        void arrival_blocked_past_the_receipt_timeout_should_fail_retryable_as_a_statement_timeout()
                throws SQLException {
            store.recordArrival(arrival(messageId, 1, share()));
            final TransactionTemplate oneSecond = new TransactionTemplate(transactionManager);
            oneSecond.setTimeout(1);
            final JdbcReceiptStore bounded = new JdbcReceiptStore(jdbc, oneSecond);

            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                try (PreparedStatement lock = holder.prepareStatement(
                        "SELECT 1 FROM event_receipt WHERE message_id = ? FOR UPDATE")) {
                    lock.setString(1, messageId);
                    lock.executeQuery().close();
                }

                assertThatThrownBy(() -> bounded.recordArrival(arrival(messageId, 2, share())))
                        .isInstanceOfSatisfying(RetryableIntakeException.class, failure -> {
                            assertThat(failure.getStage()).isEqualTo(IntakeStage.RECEIPT);
                            assertThat(failure.getFailureCause()).isEqualTo(IntakeFailureCause.STATEMENT_TIMEOUT);
                        });
                holder.rollback();
            }
            assertThat(row(messageId)).as("the timed-out delivery left nothing").containsEntry("attempts", 1);
        }
    }

    private Arrival arrival(final String id, final int deliveryCount, final String text) {
        return new Arrival(id, deliveryCount, text, parser.read(text));
    }

    private String share() {
        return """
                {"hearing": {"id": "%s"}, "hearingDay": "%s", "sharedTime": "%s"}"""
                .formatted(hearingId, HEARING_DAY, SHARED_TIME);
    }

    private Map<String, Object> row(final String key) {
        return jdbc.sql("SELECT * FROM event_receipt WHERE message_id = :key")
                .param("key", key)
                .query()
                .singleRow();
    }

    private int count(final String key) {
        return jdbc.sql("SELECT count(*) FROM event_receipt WHERE message_id = :key")
                .param("key", key)
                .query(Integer.class)
                .single();
    }

    private Instant sharedAt(final String key) {
        return jdbc.sql("SELECT shared_at FROM event_receipt WHERE message_id = :key")
                .param("key", key)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private static Instant instant(final Map<String, Object> row, final String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }
}
