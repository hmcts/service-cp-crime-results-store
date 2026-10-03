package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractor;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Duplicate;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Stored;
import uk.gov.hmcts.cp.resultsstore.domain.DefendantRef;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.KeyDetails;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;

/**
 * The store transaction on PostgreSQL (FR-013 to FR-017, FR-020; SC-008): the share, its payload,
 * its defendant rows, the day row and the receipt, all or nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("share store")
class JdbcShareStoreIT {

    private static final String HEARING_DAY = "2026-10-02";

    private static final String SHARED_TIME = "2026-10-02T14:19:50.706Z";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcReceiptStore receipts;

    private JdbcShareStore store;

    private UUID hearingId;

    @DynamicPropertySource
    static void database(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @BeforeEach
    void emptyTables() {
        jdbc.sql("TRUNCATE event_receipt, share_defendant, hearing_share_payload, hearing_share, hearing_day_head")
                .update();
        receipts = new JdbcReceiptStore(jdbc, new TransactionTemplate(transactionManager));
        store = new JdbcShareStore(jdbc, new TransactionTemplate(transactionManager), receipts,
                JdbcShareStore.Timeouts.DEFAULTS);
        hearingId = UUID.randomUUID();
    }

    @Test
    void one_share_should_write_the_share_payload_defendants_day_row_and_receipt() {
        final String text = SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME);
        final StoreRequest request = received("ID:1", text);

        final StoreResult result = store.store(request);

        final Map<String, Object> share = share(request.shareId());
        assertThat(result).isEqualTo(new Stored(request.shareId(), instant(share, "stored_at"), false, false));
        assertThat(share)
                .containsEntry("hearing_id", hearingId)
                .containsEntry("hearing_day", Date.valueOf(HEARING_DAY))
                .containsEntry("shared_day_london", Date.valueOf(HEARING_DAY))
                .containsEntry("shared_day_utc", Date.valueOf(HEARING_DAY))
                .containsEntry("payload_sha256", PayloadChecksum.sha256Hex(text))
                .containsEntry("is_reshare", false)
                .containsEntry("court_centre_id", SampleShares.COURT_CENTRE)
                .containsEntry("court_room_id", SampleShares.COURT_ROOM)
                .containsEntry("lja_code", "2577")
                .containsEntry("jurisdiction_type", "MAGISTRATES")
                .containsEntry("is_sjp", null)
                .containsEntry("is_group_proceedings", null)
                .containsEntry("youth_court_id", null)
                .containsEntry("any_subject_is_youth", false)
                .containsEntry("is_latest", true)
                .containsEntry("predecessor_share_id", null)
                .containsEntry("arrived_out_of_order", false)
                .containsEntry("enrichment_applied", false)
                .containsEntry("projection_status", "OK")
                .containsEntry("projection_reason", null)
                .containsEntry("projection_version", KeyDetailsExtractor.EXTRACTOR_VERSION)
                .containsEntry("projection_attempts", 1);
        assertThat(instant(share, "shared_at")).isEqualTo(Instant.parse(SHARED_TIME));
        assertThat(payload(request.shareId(), text))
                .containsEntry("text_matches", true)
                .containsEntry("text_bytes", text.getBytes(StandardCharsets.UTF_8).length)
                .containsEntry("parsed_matches", true);
        assertThat(defendants(request.shareId())).containsExactly(List.of(SampleShares.CASE_ID,
                SampleShares.DEFENDANT_ID, SampleShares.MASTER_DEFENDANT_ID));
        assertThat(day()).containsEntry("latest_share_id", request.shareId()).containsEntry("share_count", 1);
        assertThat(receipt("ID:1")).containsEntry("status", "STORED").containsEntry("share_id", request.shareId());
    }

    @Test
    void share_with_an_offset_shared_time_should_bind_its_identity_as_parsed() {
        final StoreRequest request = received("ID:1",
                SampleShares.share(hearingId, HEARING_DAY, "2026-10-02T00:30:00.123456+01:00"));

        store.store(request);

        final Map<String, Object> share = share(request.shareId());
        assertThat(instant(share, "shared_at")).isEqualTo(Instant.parse("2026-10-01T23:30:00.123456Z"));
        assertThat(share)
                .containsEntry("hearing_day", Date.valueOf(HEARING_DAY))
                .containsEntry("shared_day_london", Date.valueOf("2026-10-02"))
                .containsEntry("shared_day_utc", Date.valueOf("2026-10-01"));
    }

    @Test
    void identity_already_stored_should_mark_the_receipt_duplicate_with_the_stored_share_id() {
        final StoreRequest first = received("ID:1", SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME));
        store.store(first);
        // The same instant spelt another way gives another share id (FR-012).
        final StoreRequest second = received("ID:2",
                SampleShares.share(hearingId, HEARING_DAY, "2026-10-02T14:19:50.7060Z"));
        assertThat(second.shareId()).isNotEqualTo(first.shareId());

        final StoreResult result = store.store(second);

        assertThat(result).isEqualTo(new Duplicate(first.shareId()));
        assertThat(receipt("ID:2")).containsEntry("status", "DUPLICATE").containsEntry("share_id", first.shareId());
        assertThat(count("hearing_share")).isEqualTo(1);
        assertThat(count("hearing_share_payload")).isEqualTo(1);
        assertThat(count("share_defendant")).isEqualTo(1);
        assertThat(day()).containsEntry("latest_share_id", first.shareId()).containsEntry("share_count", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\\u0000b", "\\uD800", "\\udc00x"})
    void payload_that_jsonb_refuses_should_be_stored_as_text_with_no_parsed_copy(final String note) {
        final String text = SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME, "false", note);
        final StoreRequest request = received("ID:1", text);

        final StoreResult result = store.store(request);

        assertThat(result).isInstanceOfSatisfying(Stored.class,
                stored -> assertThat(stored.parsedCopySkipped()).isTrue());
        assertThat(payload(request.shareId(), text))
                .containsEntry("text_matches", true)
                .containsEntry("parsed_matches", null);
        assertThat(share(request.shareId())).containsEntry("projection_status", "OK");
    }

    /** JSON the parser takes but {@code jsonb} refuses past the escape check: numbers out of its range. */
    @ParameterizedTest
    @ValueSource(strings = {"1e1000000", "-1e1000000", "1e-1000000"})
    void payload_whose_number_jsonb_refuses_should_be_stored_as_text_with_no_parsed_copy(final String number) {
        final String text = SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME)
                .replace("\"isReshare\":false", "\"isReshare\":false,\"big\":" + number);
        assertThat(NulSafety.isJsonbSafe(text)).isTrue();
        final StoreRequest request = received("ID:1", text);

        final StoreResult result = store.store(request);

        assertThat(result).isInstanceOfSatisfying(Stored.class,
                stored -> assertThat(stored.parsedCopySkipped()).isTrue());
        assertThat(payload(request.shareId(), text))
                .containsEntry("text_matches", true)
                .containsEntry("parsed_matches", null);
        assertThat(defendants(request.shareId())).hasSize(1);
        assertThat(day()).containsEntry("latest_share_id", request.shareId()).containsEntry("share_count", 1);
        assertThat(receipt("ID:1")).containsEntry("status", "STORED").containsEntry("share_id", request.shareId());
    }

    @Test
    void payload_of_two_point_four_megabytes_should_be_stored_byte_for_byte() {
        final String note = "é€ abc ".repeat(240_000);
        final String text = SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME, "false", note);
        assertThat(text.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(2_400_000);
        final StoreRequest request = received("ID:1", text);

        store.store(request);

        // Compared in the database and by checksum, so a failure never prints the payload.
        final Map<String, Object> stored = jdbc.sql("""
                SELECT p.payload_text = :text AS text_matches, p.text_bytes, s.payload_sha256,
                       encode(sha256(convert_to(p.payload_text, 'UTF8')), 'hex') AS database_sha256
                  FROM hearing_share_payload p JOIN hearing_share s USING (share_id)
                 WHERE share_id = :shareId
                """).param("text", text).param("shareId", request.shareId()).query().singleRow();
        assertThat(stored)
                .containsEntry("text_matches", true)
                .containsEntry("text_bytes", text.getBytes(StandardCharsets.UTF_8).length)
                .containsEntry("payload_sha256", PayloadChecksum.sha256Hex(text))
                .containsEntry("database_sha256", PayloadChecksum.sha256Hex(text));
    }

    @Test
    void share_whose_key_details_failed_should_be_stored_failed_as_the_request_says() {
        final StoreRequest read = received("ID:1", SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME));
        // The store writes the projection it is given: it never extracts (FR-021).
        final StoreRequest request = with(read, new Projection.Failed("WRONG_TYPE:hearing.isSJPHearing",
                ExtractionFailureKind.WRONG_TYPE));

        store.store(request);

        assertThat(share(request.shareId()))
                .containsEntry("projection_status", "FAILED")
                .containsEntry("projection_reason", "WRONG_TYPE:hearing.isSJPHearing")
                .containsEntry("court_centre_id", null)
                .containsEntry("lja_code", null)
                .containsEntry("is_reshare", null)
                .containsEntry("any_subject_is_youth", null)
                .containsEntry("is_latest", true);
        assertThat(count("share_defendant")).isZero();
        assertThat(receipt("ID:1")).containsEntry("status", "STORED");
    }

    @Test
    void failure_part_way_should_leave_nothing_and_the_receipt_received() {
        final StoreRequest read = received("ID:1", SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME));
        // The second defendant row repeats the first's key, so the insert fails after the share,
        // its payload and the day row are written.
        final DefendantRef defendant = new DefendantRef(SampleShares.CASE_ID, SampleShares.DEFENDANT_ID, null);
        final StoreRequest request =
                with(read, new Projection.Extracted(KeyDetails.NONE, List.of(defendant, defendant), null));

        assertThatThrownBy(() -> store.store(request))
                .isInstanceOfSatisfying(RetryableIntakeException.class, failure -> {
                    assertThat(failure.getStage()).isEqualTo(IntakeStage.STORE);
                    assertThat(failure.getFailureCause()).isEqualTo(IntakeFailureCause.DATABASE);
                });

        assertThat(count("hearing_share")).isZero();
        assertThat(count("hearing_share_payload")).isZero();
        assertThat(count("share_defendant")).isZero();
        assertThat(count("hearing_day_head")).isZero();
        assertThat(receipt("ID:1")).containsEntry("status", "RECEIVED").containsEntry("share_id", null);
    }

    @Test
    void share_whose_receipt_is_not_received_should_fail_and_leave_nothing() {
        final StoreRequest request = SampleShares.request("ID:none",
                SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME));

        assertThatThrownBy(() -> store.store(request)).isInstanceOf(IllegalStateException.class);

        assertThat(count("hearing_share")).isZero();
        assertThat(count("hearing_day_head")).isZero();
    }

    private StoreRequest received(final String messageId, final String text) {
        receipts.recordArrival(SampleShares.arrival(messageId, text));
        return SampleShares.request(messageId, text);
    }

    private static StoreRequest with(final StoreRequest request, final Projection projection) {
        return new StoreRequest(request.messageId(), request.identity(), request.shareId(), request.sharedDays(),
                request.checksum(), request.text(), projection);
    }

    private Map<String, Object> share(final UUID shareId) {
        return jdbc.sql("SELECT * FROM hearing_share WHERE share_id = :shareId")
                .param("shareId", shareId).query().singleRow();
    }

    /** The payload row, its text compared in the database so a failure never prints the payload. */
    private Map<String, Object> payload(final UUID shareId, final String text) {
        return jdbc.sql("""
                SELECT payload_text = :text AS text_matches, text_bytes,
                       CASE WHEN payload_json IS NOT NULL THEN payload_json = CAST(payload_text AS jsonb) END
                           AS parsed_matches
                  FROM hearing_share_payload WHERE share_id = :shareId
                """).param("text", text).param("shareId", shareId).query().singleRow();
    }

    private List<List<Object>> defendants(final UUID shareId) {
        return jdbc.sql("""
                SELECT case_id, defendant_id, master_defendant_id FROM share_defendant
                 WHERE share_id = :shareId ORDER BY case_id, defendant_id
                """).param("shareId", shareId)
                .query((row, rowNumber) -> List.<Object>of(row.getObject(1, UUID.class),
                        row.getObject(2, UUID.class), row.getObject(3, UUID.class)))
                .list();
    }

    private Map<String, Object> day() {
        return jdbc.sql("SELECT * FROM hearing_day_head WHERE hearing_id = :hearingId AND hearing_day = :day")
                .param("hearingId", hearingId).param("day", LocalDate.parse(HEARING_DAY))
                .query().singleRow();
    }

    private Map<String, Object> receipt(final String messageId) {
        return jdbc.sql("SELECT * FROM event_receipt WHERE message_id = :messageId")
                .param("messageId", messageId).query().singleRow();
    }

    private int count(final String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private static Instant instant(final Map<String, Object> row, final String column) {
        final Object value = row.get(column);
        return value instanceof OffsetDateTime offset ? offset.toInstant() : ((Timestamp) value).toInstant();
    }
}
