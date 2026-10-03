package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.ApplicationAnswer;
import uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractor;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Duplicate;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.EnrichedCopyRefused;
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

    private static final String APPLICATION_ID = "5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d";

    /** Data-model invariant 2: the flag never true with no working copy. */
    private static final String FLAG_WITHOUT_COPY = """
            SELECT count(*) FROM hearing_share s JOIN hearing_share_payload p USING (share_id)
             WHERE s.enrichment_applied AND p.payload_json IS NULL
            """;

    /** Data-model invariant 4, guarded so a text jsonb cannot hold is never cast. */
    private static final String UNENRICHED_COPY_DIFFERS = """
            SELECT count(*) FROM hearing_share s JOIN hearing_share_payload p USING (share_id)
             WHERE NOT s.enrichment_applied
               AND CASE WHEN p.payload_json IS NOT NULL THEN p.payload_json <> CAST(p.payload_text AS jsonb)
                        ELSE FALSE END
            """;

    private final ShareIdentityParser parser = new ShareIdentityParser(JsonMapper.builder().build());

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

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

    @AfterEach
    void dataModelInvariantsHold() {
        assertThat(jdbc.sql(FLAG_WITHOUT_COPY).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql(UNENRICHED_COPY_DIFFERS).query(Integer.class).single()).isZero();
    }

    @Test
    void one_share_should_write_the_share_payload_defendants_day_row_and_receipt() {
        final String text = SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME);
        final StoreRequest request = received("ID:1", text);

        final StoreResult result = store.store(request);

        final Map<String, Object> share = share(request.shareId());
        assertThat(result).isEqualTo(new Stored(request.shareId(), instant(share, "stored_at"), false, false, false));
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
    void enriched_share_should_keep_the_arrived_text_and_checksum_and_store_the_working_copy_and_flag() {
        final String text = SampleShares.shareWithApplication(hearingId, HEARING_DAY, SHARED_TIME, APPLICATION_ID);
        final StoreRequest request = enriched("ID:1", text, SampleShares.finalised("Granted"));
        assertThat(request.enrichmentApplied()).isTrue();

        final StoreResult result = store.store(request);

        final Map<String, Object> share = share(request.shareId());
        assertThat(result).isEqualTo(new Stored(request.shareId(), instant(share, "stored_at"), false, false, true));
        assertThat(share)
                .containsEntry("payload_sha256", PayloadChecksum.sha256Hex(text))
                .containsEntry("enrichment_applied", true);
        assertThat(workingCopy(request.shareId(), text, request.parsedCopy()))
                .containsEntry("text_matches", true)
                .containsEntry("text_bytes", text.getBytes(StandardCharsets.UTF_8).length)
                .containsEntry("database_sha256", PayloadChecksum.sha256Hex(text))
                .containsEntry("working_matches", true)
                .containsEntry("parsed_matches", false)
                .containsEntry("results", 1)
                .containsEntry("amended", false);
        assertThat(defendants(request.shareId())).hasSize(1);
        assertThat(receipt("ID:1")).containsEntry("status", "STORED");
    }

    @Test
    void share_with_nothing_added_should_store_the_cast_of_its_text_and_the_flag_false() {
        final String text = SampleShares.shareWithApplication(hearingId, HEARING_DAY, SHARED_TIME, APPLICATION_ID);
        final StoreRequest request = enriched("ID:1", text, new ApplicationAnswer.NotFound());
        assertThat(request.parsedCopy()).isEqualTo(text);

        final StoreResult result = store.store(request);

        assertThat(result).isInstanceOfSatisfying(Stored.class,
                stored -> assertThat(stored.enrichmentApplied()).isFalse());
        assertThat(share(request.shareId())).containsEntry("enrichment_applied", false);
        assertThat(payload(request.shareId(), text)).containsEntry("parsed_matches", true);
    }

    @Test
    void enriched_copy_holding_an_escaped_nul_should_be_refused_before_any_transaction_and_write_nothing() {
        final String text = SampleShares.shareWithApplication(hearingId, HEARING_DAY, SHARED_TIME, APPLICATION_ID);
        final StoreRequest request = enriched("ID:1", text, SampleShares.finalised("a\\u0000b"));
        assertThat(request.enrichmentApplied()).isTrue();
        final AtomicInteger transactions = new AtomicInteger();
        final TransactionTemplate template = new TransactionTemplate(transactionManager);
        final TransactionOperations counting = new TransactionOperations() {
            @Override
            public <T> T execute(final TransactionCallback<T> action) {
                transactions.incrementAndGet();
                return template.execute(action);
            }
        };
        final JdbcShareStore counted = new JdbcShareStore(jdbc, counting, receipts, JdbcShareStore.Timeouts.DEFAULTS);

        final StoreResult result = counted.store(request);

        assertThat(result).isEqualTo(new EnrichedCopyRefused());
        assertThat(transactions).hasValue(0);
        assertNothingWritten();
    }

    @Test
    void enriched_copy_the_database_refuses_should_roll_back_and_leave_the_connection_usable() throws SQLException {
        final String text = SampleShares.shareWithApplication(hearingId, HEARING_DAY, SHARED_TIME, APPLICATION_ID);
        // Passes the escape check, but jsonb refuses the number as out of range (SQLSTATE 22003).
        final StoreRequest request = enriched("ID:1", text, new ApplicationAnswer.Found(parser.readTree(
                "{\"applicationStatus\":\"FINALISED\",\"judicialResults\":[{\"big\":1e1000000}]}")));
        assertThat(NulSafety.isJsonbSafe(request.parsedCopy())).isTrue();
        // One pooled connection for both calls; closing the wrapper hands it back to the pool.
        try (SingleConnectionDataSource single = new SingleConnectionDataSource(dataSource.getConnection(), true)) {
            final JdbcClient singleJdbc = JdbcClient.create(single);
            final TransactionTemplate singleTransaction =
                    new TransactionTemplate(new DataSourceTransactionManager(single));
            final JdbcShareStore onOneConnection = new JdbcShareStore(singleJdbc, singleTransaction,
                    new JdbcReceiptStore(singleJdbc, singleTransaction), JdbcShareStore.Timeouts.DEFAULTS);

            final StoreResult refused = onOneConnection.store(request);

            assertThat(refused).isEqualTo(new EnrichedCopyRefused());
            assertNothingWritten();
            final StoreResult rerun = onOneConnection.store(SampleShares.request("ID:1", text));
            assertThat(rerun).isInstanceOfSatisfying(Stored.class,
                    stored -> assertThat(stored.enrichmentApplied()).isFalse());
        }
        assertThat(share(request.shareId())).containsEntry("enrichment_applied", false);
        assertThat(payload(request.shareId(), text)).containsEntry("parsed_matches", true);
        assertThat(receipt("ID:1")).containsEntry("status", "STORED");
    }

    @Test
    void duplicate_of_an_enriched_share_should_keep_the_first_payload() {
        final String text = SampleShares.shareWithApplication(hearingId, HEARING_DAY, SHARED_TIME, APPLICATION_ID);
        final StoreRequest first = enriched("ID:1", text, SampleShares.finalised("Granted"));
        store.store(first);
        final StoreRequest second = enriched("ID:2", text, SampleShares.finalised("Refused"));

        final StoreResult result = store.store(second);

        assertThat(result).isEqualTo(new Duplicate(first.shareId()));
        assertThat(count("hearing_share_payload")).isEqualTo(1);
        assertThat(workingCopy(first.shareId(), text, first.parsedCopy())).containsEntry("working_matches", true);
        assertThat(receipt("ID:2")).containsEntry("status", "DUPLICATE");
    }

    @Test
    void stored_share_id_should_find_a_stored_share_and_be_empty_otherwise() {
        final StoreRequest request = received("ID:1", SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME));
        assertThat(store.storedShareId(request.identity())).isEmpty();

        store.store(request);

        assertThat(store.storedShareId(request.identity())).contains(request.shareId());
    }

    @Test
    void stored_share_id_should_answer_while_another_transaction_holds_the_day_lock() {
        final StoreRequest request = received("ID:1", SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME));
        store.store(request);

        final Optional<UUID> found = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.sql("SELECT share_count FROM hearing_day_head WHERE hearing_id = :hearingId AND hearing_day = :day "
                    + "FOR UPDATE").param("hearingId", hearingId).param("day", LocalDate.parse(HEARING_DAY))
                    .query(Integer.class).single();
            return CompletableFuture.supplyAsync(() -> store.storedShareId(request.identity()))
                    .orTimeout(5, TimeUnit.SECONDS).join();
        });

        assertThat(found).contains(request.shareId());
    }

    @Test
    void stored_share_id_and_payload_for_extraction_should_classify_a_database_failure_at_store()
            throws SQLException {
        final StoreRequest request = received("ID:1", SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME));
        final DataSource unreachable = mock(DataSource.class);
        when(unreachable.getConnection()).thenThrow(new SQLException("unreachable", "08001"));
        final JdbcShareStore failing = new JdbcShareStore(JdbcClient.create(unreachable),
                new TransactionTemplate(transactionManager), receipts, JdbcShareStore.Timeouts.DEFAULTS);

        assertThatThrownBy(() -> failing.storedShareId(request.identity()))
                .isInstanceOfSatisfying(RetryableIntakeException.class, failure -> {
                    assertThat(failure.getStage()).isEqualTo(IntakeStage.STORE);
                    assertThat(failure.getFailureCause()).isEqualTo(IntakeFailureCause.DATABASE);
                });
        assertThatThrownBy(() -> failing.payloadForExtraction(request.shareId()))
                .isInstanceOfSatisfying(RetryableIntakeException.class,
                        failure -> assertThat(failure.getStage()).isEqualTo(IntakeStage.STORE));
    }

    @Test
    void payload_for_extraction_should_prefer_the_working_copy() {
        final String text = SampleShares.shareWithApplication(hearingId, HEARING_DAY, SHARED_TIME, APPLICATION_ID);
        final StoreRequest request = enriched("ID:1", text, SampleShares.finalised("Granted"));
        store.store(request);

        final String copy = store.payloadForExtraction(request.shareId());

        // Compared as booleans, so a failure prints no payload.
        assertThat(parser.readTree(copy).equals(parser.readTree(request.parsedCopy()))).isTrue();
        assertThat(parser.readTree(copy).equals(parser.readTree(text))).isFalse();
    }

    @Test
    void payload_for_extraction_should_fall_back_to_the_text_when_there_is_no_working_copy() {
        final String text = SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME, "false", "a\\u0000b");
        final StoreRequest request = received("ID:1", text);
        store.store(request);

        assertThat(store.payloadForExtraction(request.shareId()).equals(text)).isTrue();
    }

    @Test
    void payload_for_extraction_of_a_share_with_no_payload_row_should_throw_the_row_s_own_failure() {
        assertThatThrownBy(() -> store.payloadForExtraction(UUID.randomUUID()))
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);
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

        assertThat(result).isInstanceOfSatisfying(Stored.class, stored -> {
            assertThat(stored.parsedCopySkipped()).isTrue();
            assertThat(stored.enrichmentApplied()).isFalse();
        });
        assertThat(payload(request.shareId(), text))
                .containsEntry("text_matches", true)
                .containsEntry("parsed_matches", null);
        assertThat(share(request.shareId()))
                .containsEntry("projection_status", "OK")
                .containsEntry("enrichment_applied", false);
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

    /**
     * The parsed-copy savepoint swallows only a data exception (SQLSTATE class 22). A test-only trigger
     * refuses the payload row with its parsed copy as a statement timeout, which must fail the whole
     * transaction rather than be stored as text with no parsed copy.
     */
    @Test
    void parsed_copy_failure_that_is_not_a_data_exception_should_leave_nothing_and_the_receipt_received() {
        final StoreRequest request = received("ID:1", SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME));

        assertStatementTimeoutWhenThePayloadRowHasAParsedCopy(request);

        assertNothingWritten();
    }

    /**
     * Only a data exception (SQLSTATE class 22) on the enriched copy is "refused". A statement timeout
     * on the same insert is an operational failure: it must escape as a retryable store failure, not
     * turn into a silent fallback to the arrived copy.
     */
    @Test
    void enriched_copy_failure_that_is_not_a_data_exception_should_throw_retryable_and_leave_nothing() {
        final String text = SampleShares.shareWithApplication(hearingId, HEARING_DAY, SHARED_TIME, APPLICATION_ID);
        final StoreRequest request = enriched("ID:1", text, SampleShares.finalised("Granted"));
        assertThat(request.enrichmentApplied()).isTrue();

        assertStatementTimeoutWhenThePayloadRowHasAParsedCopy(request);

        assertNothingWritten();
    }

    /** A test-only trigger refuses any payload row with a parsed copy as a statement timeout (57014). */
    private void assertStatementTimeoutWhenThePayloadRowHasAParsedCopy(final StoreRequest request) {
        jdbc.sql("""
                CREATE FUNCTION test_refuse_parsed_copy() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload_json IS NOT NULL THEN
                        RAISE EXCEPTION 'test timeout' USING ERRCODE = '57014';
                    END IF;
                    RETURN NEW;
                END $$
                """).update();
        try {
            jdbc.sql("""
                    CREATE TRIGGER test_refuse_parsed_copy_tg BEFORE INSERT ON hearing_share_payload
                        FOR EACH ROW EXECUTE FUNCTION test_refuse_parsed_copy()
                    """).update();

            assertThatThrownBy(() -> store.store(request))
                    .isInstanceOfSatisfying(RetryableIntakeException.class, failure -> {
                        assertThat(failure.getStage()).isEqualTo(IntakeStage.STORE);
                        assertThat(failure.getFailureCause()).isEqualTo(IntakeFailureCause.STATEMENT_TIMEOUT);
                    });
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS test_refuse_parsed_copy_tg ON hearing_share_payload").update();
            jdbc.sql("DROP FUNCTION test_refuse_parsed_copy()").update();
        }
    }

    @Test
    void share_whose_receipt_is_not_received_should_fail_and_leave_nothing() {
        final StoreRequest request = SampleShares.request("ID:none",
                SampleShares.share(hearingId, HEARING_DAY, SHARED_TIME));

        assertThatThrownBy(() -> store.store(request)).isInstanceOf(IllegalStateException.class);

        assertThat(count("hearing_share")).isZero();
        assertThat(count("hearing_day_head")).isZero();
    }

    private StoreRequest enriched(final String messageId, final String text, final ApplicationAnswer answer) {
        receipts.recordArrival(SampleShares.arrival(messageId, text));
        return SampleShares.enrichedRequest(messageId, text, answer);
    }

    private void assertNothingWritten() {
        assertThat(count("hearing_share")).isZero();
        assertThat(count("hearing_share_payload")).isZero();
        assertThat(count("share_defendant")).isZero();
        assertThat(count("hearing_day_head")).isZero();
        assertThat(receipt("ID:1")).containsEntry("status", "RECEIVED").containsEntry("share_id", null);
    }

    /** The payload row against the working copy, compared in the database so a failure prints no payload. */
    private Map<String, Object> workingCopy(final UUID shareId, final String text, final String parsedCopy) {
        return jdbc.sql("""
                SELECT payload_text = :text AS text_matches, text_bytes,
                       encode(sha256(convert_to(payload_text, 'UTF8')), 'hex') AS database_sha256,
                       payload_json = CAST(:parsedCopy AS jsonb) AS working_matches,
                       payload_json = CAST(payload_text AS jsonb) AS parsed_matches,
                       jsonb_array_length(payload_json -> 'hearing' -> 'courtApplications' -> 0 -> 'judicialResults')
                           AS results,
                       jsonb_exists(payload_json -> 'hearing' -> 'courtApplications' -> 0 -> 'judicialResults' -> 0,
                           'amendmentDate') AS amended
                  FROM hearing_share_payload WHERE share_id = :shareId
                """).param("text", text).param("parsedCopy", parsedCopy).param("shareId", shareId)
                .query().singleRow();
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
