package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.ExtractionSweep;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractor;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.application.SweepCandidate;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;

/**
 * The extraction sweep on PostgreSQL (FR-033 to FR-037, SC-006, US5): it selects only {@code FAILED}
 * rows due a retry, re-reads the stored text, fills the key details and defendant rows under the
 * hearing-day lock and the share row's lock, records a renewed failure, stops retrying an unexpected
 * failure at the limit, and two sweeps at once work each row once. A write that fails is operational:
 * the row's projection is left alone, only {@code sweep_tried_at} is stamped, and the row rotates
 * behind the rows not yet tried.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("extraction sweep")
class ExtractionSweepIT {

    private static final String HEARING_DAY = "2026-10-02";

    private static final int RAISED_VERSION = KeyDetailsExtractor.EXTRACTOR_VERSION + 1;

    private static final int MAX_ATTEMPTS = 3;

    private static final String COURT_CENTRE_REASON = "INVALID_UUID:hearing.courtCentre.id";

    private static final String UNEXPECTED_REASON = "UNEXPECTED:IllegalStateException";

    private static final Duration WITHIN = Duration.ofSeconds(20);

    /** Where the payload table is moved, out of reach, by the test whose read fails. */
    private static final String PAYLOAD_AWAY = "sweep_it_payload_away";

    /** A test-only constraint that refuses every defendant row; dropped by the test that adds it. */
    private static final String REFUSE_DEFENDANTS = "sweep_it_refuse_defendants_ck";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcReceiptStore receipts;

    private JdbcShareStore store;

    private UUID hearingId;

    private int messages;

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
    void selection_should_take_only_failed_rows_due_a_retry_oldest_first() {
        final UUID ok = stored("2026-10-02T09:00:00Z", null);
        final UUID olderFailed = stored("2026-10-02T10:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final UUID unexpected = stored("2026-10-02T11:00:00Z", failed(UNEXPECTED_REASON,
                ExtractionFailureKind.UNEXPECTED));

        final List<UUID> atTheRunningVersion = store.sweepCandidates(KeyDetailsExtractor.EXTRACTOR_VERSION,
                MAX_ATTEMPTS, 100).stream().map(SweepCandidate::shareId).toList();
        final List<UUID> afterARaise = store.sweepCandidates(RAISED_VERSION, MAX_ATTEMPTS, 100).stream()
                .map(SweepCandidate::shareId).toList();
        final List<UUID> oneAtATime = store.sweepCandidates(RAISED_VERSION, MAX_ATTEMPTS, 1).stream()
                .map(SweepCandidate::shareId).toList();
        final List<SweepCandidate> withNoRetriesLeft = store.sweepCandidates(KeyDetailsExtractor.EXTRACTOR_VERSION,
                1, 100);

        assertThat(atTheRunningVersion).containsExactly(unexpected);
        assertThat(afterARaise).containsExactly(olderFailed, unexpected).doesNotContain(ok);
        assertThat(oneAtATime).containsExactly(olderFailed);
        assertThat(withNoRetriesLeft).isEmpty();
        assertThat(store.sweepCandidates(RAISED_VERSION, MAX_ATTEMPTS, 100).getFirst())
                .isEqualTo(new SweepCandidate(olderFailed, hearingId, LocalDate.parse(HEARING_DAY), 1));
    }

    @Test
    void failed_row_should_become_ok_after_a_version_raise_with_its_details_defendants_and_day_flags() {
        final UUID shareId = stored("2026-10-02T10:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final Map<String, Object> before = share(shareId);
        assertThat(day()).containsEntry("youth_seen", null);

        final List<SweepRowOutcome> outcomes = sweep(new KeyDetailsExtractor(), RAISED_VERSION).runRound();

        assertThat(outcomes).containsExactly(SweepRowOutcome.FIXED);
        final Map<String, Object> after = share(shareId);
        assertThat(after)
                .containsEntry("projection_status", "OK")
                .containsEntry("projection_reason", null)
                .containsEntry("projection_version", RAISED_VERSION)
                .containsEntry("projection_attempts", 2)
                .containsEntry("court_centre_id", SampleShares.COURT_CENTRE)
                .containsEntry("court_room_id", SampleShares.COURT_ROOM)
                .containsEntry("lja_code", "2577")
                .containsEntry("jurisdiction_type", "MAGISTRATES")
                .containsEntry("is_reshare", false)
                .containsEntry("any_subject_is_youth", false)
                .containsEntry("day_youth_seen", false)
                .containsEntry("is_latest", true)
                .containsEntry("stored_at", before.get("stored_at"))
                .containsEntry("stored_seq", before.get("stored_seq"));
        assertThat(instant(after, "projected_at")).isAfter(instant(before, "projected_at"));
        assertThat(defendants(shareId)).containsExactly(List.of(SampleShares.CASE_ID, SampleShares.DEFENDANT_ID,
                SampleShares.MASTER_DEFENDANT_ID));
        assertThat(day()).containsEntry("youth_seen", false).containsEntry("share_count", 1);
        assertThat(sweep(new KeyDetailsExtractor(), RAISED_VERSION).runRound()).isEmpty();
    }

    @Test
    void row_that_fails_again_should_record_the_new_reason_version_and_attempts_and_stay_empty() {
        final UUID shareId = stored("2026-10-02T10:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final Projection.Failed wrongType = failed("WRONG_TYPE:hearing.isSJPHearing", ExtractionFailureKind.WRONG_TYPE);

        final List<SweepRowOutcome> outcomes = sweep(always(wrongType), RAISED_VERSION).runRound();

        assertThat(outcomes).containsExactly(SweepRowOutcome.FAILED_AGAIN);
        assertThat(share(shareId))
                .containsEntry("projection_status", "FAILED")
                .containsEntry("projection_reason", "WRONG_TYPE:hearing.isSJPHearing")
                .containsEntry("projection_version", RAISED_VERSION)
                .containsEntry("projection_attempts", 2)
                .containsEntry("court_centre_id", null)
                .containsEntry("any_subject_is_youth", null);
        assertThat(defendants(shareId)).isEmpty();
        assertThat(sweep(always(wrongType), RAISED_VERSION).runRound()).isEmpty();
    }

    @Test
    void unexpected_failure_should_be_retried_until_three_attempts_have_been_made() {
        final Projection.Failed unexpected = failed(UNEXPECTED_REASON, ExtractionFailureKind.UNEXPECTED);
        final UUID shareId = stored("2026-10-02T10:00:00Z", unexpected);
        final ExtractionSweep sweep = sweep(always(unexpected), KeyDetailsExtractor.EXTRACTOR_VERSION);

        final List<SweepRowOutcome> second = sweep.runRound();
        final List<SweepRowOutcome> third = sweep.runRound();
        final List<SweepRowOutcome> fourth = sweep.runRound();

        assertThat(second).containsExactly(SweepRowOutcome.FAILED_AGAIN);
        assertThat(third).containsExactly(SweepRowOutcome.FAILED_AGAIN);
        assertThat(fourth).isEmpty();
        assertThat(share(shareId))
                .containsEntry("projection_status", "FAILED")
                .containsEntry("projection_reason", UNEXPECTED_REASON)
                .containsEntry("projection_attempts", MAX_ATTEMPTS);
        assertThat(share(shareId).get("sweep_tried_at")).isNotNull();
    }

    @Test
    void unexpected_failure_that_reads_on_retry_should_become_ok() {
        final UUID shareId = stored("2026-10-02T10:00:00Z", failed(UNEXPECTED_REASON,
                ExtractionFailureKind.UNEXPECTED));

        final List<SweepRowOutcome> outcomes =
                sweep(new KeyDetailsExtractor(), KeyDetailsExtractor.EXTRACTOR_VERSION).runRound();

        assertThat(outcomes).containsExactly(SweepRowOutcome.FIXED);
        assertThat(share(shareId)).containsEntry("projection_status", "OK")
                .containsEntry("projection_version", KeyDetailsExtractor.EXTRACTOR_VERSION)
                .containsEntry("projection_attempts", 2);
    }

    @Test
    void fixed_row_should_turn_the_day_flag_true_on_every_share_of_the_day() {
        final UUID older = stored("2026-10-02T09:00:00Z", null);
        final String youthText = SampleShares.share(hearingId, HEARING_DAY, "2026-10-02T10:00:00Z", "true", "");
        final UUID failedYouth = stored(youthText, failed(COURT_CENTRE_REASON, ExtractionFailureKind.INVALID_UUID));
        assertThat(day()).containsEntry("youth_seen", null);

        sweep(new KeyDetailsExtractor(), RAISED_VERSION).runRound();

        assertThat(share(failedYouth)).containsEntry("any_subject_is_youth", true).containsEntry("day_youth_seen", true);
        assertThat(share(older)).containsEntry("any_subject_is_youth", false).containsEntry("day_youth_seen", true);
        assertThat(day()).containsEntry("youth_seen", true);
    }

    @Test
    void two_sweeps_at_once_should_work_each_row_once() throws Exception {
        final UUID shareId = stored("2026-10-02T10:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        // Closed in reverse: the held day is released first, then the pool waits for both rounds.
        try (ExecutorService pods = Executors.newFixedThreadPool(2); Connection holder = dataSource.getConnection()) {
            holdTheDay(holder);
            final List<Future<List<SweepRowOutcome>>> rounds = new ArrayList<>();
            rounds.add(pods.submit(() -> sweep(new KeyDetailsExtractor(), RAISED_VERSION).runRound()));
            rounds.add(pods.submit(() -> sweep(new KeyDetailsExtractor(), RAISED_VERSION).runRound()));
            // Both have selected the row and wait for the day lock: the overlap is certain, not timed.
            await().atMost(WITHIN).until(() -> waitingForALock() == 2);
            holder.rollback();

            final List<SweepRowOutcome> outcomes = new ArrayList<>();
            for (final Future<List<SweepRowOutcome>> round : rounds) {
                outcomes.addAll(round.get());
            }
            assertThat(outcomes).containsExactlyInAnyOrder(SweepRowOutcome.FIXED, SweepRowOutcome.SKIPPED);
        }
        assertThat(share(shareId)).containsEntry("projection_status", "OK").containsEntry("projection_attempts", 2);
        assertThat(defendants(shareId)).hasSize(1);
    }

    @Test
    void row_changed_since_it_was_selected_should_be_skipped_and_left_alone() {
        final UUID shareId = stored("2026-10-02T10:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final SweepCandidate selected = store.sweepCandidates(RAISED_VERSION, MAX_ATTEMPTS, 10).getFirst();
        final Projection.Failed again = failed(COURT_CENTRE_REASON, ExtractionFailureKind.INVALID_UUID);
        store.recordReextraction(selected, again, RAISED_VERSION);

        final SweepRowOutcome outcome = store.recordReextraction(selected, again, RAISED_VERSION);

        assertThat(outcome).isEqualTo(SweepRowOutcome.SKIPPED);
        assertThat(share(shareId)).containsEntry("projection_attempts", 2);
    }

    @Test
    void row_whose_stored_identity_the_parser_now_refuses_should_be_read_and_not_reselected() {
        final String valid = SampleShares.share(hearingId, HEARING_DAY, "2026-10-02T10:00:00Z");
        // Stored as it arrived; read today, its sharedTime (an offset without minutes) would be refused.
        final String refusedToday = valid.replace("2026-10-02T10:00:00Z", "2026-10-02T11:00:00+01");
        final UUID shareId = storedWithText(valid, refusedToday, failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        assertThat(new ShareIdentityParser(JsonMapper.builder().build()).read(refusedToday))
                .isNotInstanceOf(ShareIdentityParser.Share.class);

        final List<SweepRowOutcome> first = sweep(new KeyDetailsExtractor(), RAISED_VERSION).runRound();
        final List<SweepRowOutcome> next = sweep(new KeyDetailsExtractor(), RAISED_VERSION).runRound();

        assertThat(first).containsExactly(SweepRowOutcome.FIXED);
        assertThat(next).isEmpty();
        assertThat(share(shareId)).containsEntry("projection_status", "OK")
                .containsEntry("court_centre_id", SampleShares.COURT_CENTRE);
    }

    @Test
    void row_with_no_parsed_copy_should_be_read_from_its_stored_text() {
        // A JSON escape for U+0000: valid JSON text, but jsonb cannot hold it, so no parsed copy is kept.
        final String text = SampleShares.share(hearingId, HEARING_DAY, "2026-10-02T10:00:00Z", "false", "\\u0000");
        final UUID shareId = stored(text, failed(COURT_CENTRE_REASON, ExtractionFailureKind.INVALID_UUID));
        assertThat(jdbc.sql("SELECT payload_json IS NULL FROM hearing_share_payload WHERE share_id = :shareId")
                .param("shareId", shareId).query(Boolean.class).single()).isTrue();

        final List<SweepRowOutcome> outcomes = sweep(new KeyDetailsExtractor(), RAISED_VERSION).runRound();

        assertThat(outcomes).containsExactly(SweepRowOutcome.FIXED);
        assertThat(share(shareId)).containsEntry("projection_status", "OK")
                .containsEntry("court_centre_id", SampleShares.COURT_CENTRE)
                .containsEntry("lja_code", "2577");
        assertThat(defendants(shareId)).hasSize(1);
    }

    @Test
    void write_that_fails_should_leave_the_projection_stamp_the_try_count_an_error_and_rotate_the_row() {
        final UUID older = stored("2026-10-02T10:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final UUID newer = stored("2026-10-02T11:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final Map<String, Object> before = share(older);
        // Test only: every defendant insert fails, after the key details were set in the same transaction.
        jdbc.sql("ALTER TABLE share_defendant ADD CONSTRAINT " + REFUSE_DEFENDANTS
                + " CHECK (case_id IS NULL) NOT VALID").update();
        try {
            final IntakeObserver observer = mock(IntakeObserver.class);
            final ExtractionSweep oneAtATime = new ExtractionSweep(store,
                    new ShareIdentityParser(JsonMapper.builder().build()), new KeyDetailsExtractor(), observer,
                    new ExtractionSweep.Settings(RAISED_VERSION, MAX_ATTEMPTS, 1));

            final List<SweepRowOutcome> first = oneAtATime.runRound();

            assertThat(first).containsExactly(SweepRowOutcome.ERROR);
            verify(observer).sweepRow(SweepRowOutcome.ERROR);
            verify(observer, never()).extractionFailed(any(), any());
            final Map<String, Object> after = share(older);
            assertThat(after)
                    .containsEntry("projection_status", "FAILED")
                    .containsEntry("projection_reason", COURT_CENTRE_REASON)
                    .containsEntry("projection_version", KeyDetailsExtractor.EXTRACTOR_VERSION)
                    .containsEntry("projection_attempts", 1)
                    .containsEntry("projected_at", before.get("projected_at"))
                    .containsEntry("court_centre_id", null)
                    .containsEntry("lja_code", null)
                    .containsEntry("any_subject_is_youth", null)
                    .containsEntry("day_youth_seen", null);
            assertThat(after.get("sweep_tried_at")).isNotNull();
            assertThat(defendants(older)).isEmpty();
            assertThat(day()).containsEntry("youth_seen", null);
            assertThat(share(newer).get("sweep_tried_at")).isNull();

            // The row never tried goes first; the one that failed rotates behind it.
            assertThat(store.sweepCandidates(RAISED_VERSION, MAX_ATTEMPTS, 1))
                    .extracting(SweepCandidate::shareId).containsExactly(newer);
            assertThat(oneAtATime.runRound()).containsExactly(SweepRowOutcome.ERROR);
            assertThat(share(newer).get("sweep_tried_at")).isNotNull();
            assertThat(instant(share(older), "sweep_tried_at")).isEqualTo(instant(after, "sweep_tried_at"));
            assertThat(store.sweepCandidates(RAISED_VERSION, MAX_ATTEMPTS, 1))
                    .extracting(SweepCandidate::shareId).containsExactly(older);
        } finally {
            jdbc.sql("ALTER TABLE share_defendant DROP CONSTRAINT " + REFUSE_DEFENDANTS).update();
        }
    }

    @Test
    void payload_read_that_fails_should_leave_the_projection_stamp_the_try_and_count_an_error() {
        final UUID shareId = stored("2026-10-02T10:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final Map<String, Object> before = share(shareId);
        final IntakeObserver observer = mock(IntakeObserver.class);
        final ExtractionSweep sweep = new ExtractionSweep(store,
                new ShareIdentityParser(JsonMapper.builder().build()), new KeyDetailsExtractor(), observer,
                new ExtractionSweep.Settings(RAISED_VERSION, MAX_ATTEMPTS, 100));
        // Test only: the payload table is out of reach, so the read fails on the database, not on the row.
        jdbc.sql("ALTER TABLE hearing_share_payload RENAME TO " + PAYLOAD_AWAY).update();
        final List<SweepRowOutcome> outcomes;
        try {
            assertThatThrownBy(() -> store.payloadText(shareId)).isInstanceOf(RetryableIntakeException.class);
            outcomes = sweep.runRound();
        } finally {
            jdbc.sql("ALTER TABLE " + PAYLOAD_AWAY + " RENAME TO hearing_share_payload").update();
        }

        assertThat(outcomes).containsExactly(SweepRowOutcome.ERROR);
        verify(observer).sweepRow(SweepRowOutcome.ERROR);
        verify(observer, never()).extractionFailed(any(), any());
        final Map<String, Object> after = share(shareId);
        assertThat(after.get("sweep_tried_at")).isNotNull();
        before.remove("sweep_tried_at");
        after.remove("sweep_tried_at");
        assertThat(after).isEqualTo(before);
        assertThat(defendants(shareId)).isEmpty();
        assertThat(sweep.runRound()).containsExactly(SweepRowOutcome.FIXED);
    }

    @Test
    void payload_read_of_a_share_with_no_payload_row_should_throw_unclassified() {
        assertThatThrownBy(() -> store.payloadText(UUID.randomUUID()))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void selection_should_take_rows_never_tried_first_then_the_longest_since_tried() {
        final UUID first = stored("2026-10-02T09:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final UUID second = stored("2026-10-02T10:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final UUID third = stored("2026-10-02T11:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        triedAt(first, "2026-10-03T10:00:00Z");
        triedAt(second, "2026-10-03T09:00:00Z");

        final List<UUID> order = store.sweepCandidates(RAISED_VERSION, MAX_ATTEMPTS, 100).stream()
                .map(SweepCandidate::shareId).toList();

        assertThat(order).containsExactly(third, second, first);
    }

    @Test
    void recording_a_try_should_stamp_only_that_row_with_the_database_clock() {
        final UUID tried = stored("2026-10-02T09:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final UUID untouched = stored("2026-10-02T10:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final Map<String, Object> before = share(tried);

        store.recordSweepAttempt(tried);

        final Map<String, Object> after = share(tried);
        assertThat(instant(after, "sweep_tried_at")).isAfterOrEqualTo(instant(before, "stored_at"));
        before.remove("sweep_tried_at");
        after.remove("sweep_tried_at");
        assertThat(after).isEqualTo(before);
        assertThat(share(untouched).get("sweep_tried_at")).isNull();
    }

    @Test
    void row_whose_write_times_out_should_be_left_as_it_was_stamped_and_selected_again() throws Exception {
        final UUID shareId = stored("2026-10-02T10:00:00Z", failed(COURT_CENTRE_REASON,
                ExtractionFailureKind.INVALID_UUID));
        final JdbcShareStore impatient = new JdbcShareStore(jdbc, new TransactionTemplate(transactionManager),
                receipts, new JdbcShareStore.Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(5),
                        Duration.ofSeconds(5)));
        final ExtractionSweep sweep = new ExtractionSweep(impatient,
                new ShareIdentityParser(JsonMapper.builder().build()), new KeyDetailsExtractor(),
                mock(IntakeObserver.class), new ExtractionSweep.Settings(RAISED_VERSION, MAX_ATTEMPTS, 100));
        final Map<String, Object> before = share(shareId);

        final List<SweepRowOutcome> held;
        try (Connection holder = dataSource.getConnection()) {
            holdTheDay(holder);
            held = sweep.runRound();
            holder.rollback();
        }

        assertThat(held).containsExactly(SweepRowOutcome.ERROR);
        assertThat(share(shareId))
                .containsEntry("projection_status", "FAILED")
                .containsEntry("projection_reason", COURT_CENTRE_REASON)
                .containsEntry("projection_version", KeyDetailsExtractor.EXTRACTOR_VERSION)
                .containsEntry("projection_attempts", 1)
                .containsEntry("projected_at", before.get("projected_at"));
        assertThat(share(shareId).get("sweep_tried_at")).isNotNull();
        assertThat(defendants(shareId)).isEmpty();
        assertThat(day()).containsEntry("youth_seen", null);
        assertThat(sweep.runRound()).containsExactly(SweepRowOutcome.FIXED);
    }

    private ExtractionSweep sweep(final KeyDetailsExtractor extractor, final int version) {
        return new ExtractionSweep(store, new ShareIdentityParser(JsonMapper.builder().build()), extractor,
                mock(IntakeObserver.class), new ExtractionSweep.Settings(version, MAX_ATTEMPTS, 100));
    }

    /** An extractor that always gives the same result. */
    private static KeyDetailsExtractor always(final Projection projection) {
        return new KeyDetailsExtractor() {
            @Override
            public Projection extract(final JsonNode body) {
                return projection;
            }
        };
    }

    private static Projection.Failed failed(final String reason, final ExtractionFailureKind kind) {
        return new Projection.Failed(reason, kind);
    }

    /** Stores a share of the day at a shared time, extracted as read or with the given projection. */
    private UUID stored(final String sharedTimeOrText, final Projection projection) {
        final String text = sharedTimeOrText.startsWith("{")
                ? sharedTimeOrText
                : SampleShares.share(hearingId, HEARING_DAY, sharedTimeOrText);
        messages++;
        final String messageId = "ID:" + messages;
        receipts.recordArrival(SampleShares.arrival(messageId, text));
        final StoreRequest request = SampleShares.request(messageId, text);
        store.store(projection == null ? request : new StoreRequest(request.messageId(), request.identity(),
                request.shareId(), request.sharedDays(), request.checksum(), request.text(), projection));
        return request.shareId();
    }

    /**
     * Stores a share identified by one text whose payload is another, as if the text had been read
     * under rules since tightened.
     */
    private UUID storedWithText(final String identifiedBy, final String payload, final Projection projection) {
        messages++;
        final String messageId = "ID:" + messages;
        receipts.recordArrival(SampleShares.arrival(messageId, identifiedBy));
        final StoreRequest request = SampleShares.request(messageId, identifiedBy);
        store.store(new StoreRequest(request.messageId(), request.identity(), request.shareId(),
                request.sharedDays(), PayloadChecksum.sha256Hex(payload), payload, projection));
        return request.shareId();
    }

    private void triedAt(final UUID shareId, final String instant) {
        jdbc.sql("UPDATE hearing_share SET sweep_tried_at = :triedAt WHERE share_id = :shareId")
                .param("triedAt", OffsetDateTime.parse(instant)).param("shareId", shareId).update();
    }

    private void holdTheDay(final Connection holder) throws SQLException {
        holder.setAutoCommit(false);
        try (PreparedStatement lock = holder.prepareStatement(
                "SELECT 1 FROM hearing_day_head WHERE hearing_id = ? AND hearing_day = ? FOR UPDATE")) {
            lock.setObject(1, hearingId);
            lock.setObject(2, LocalDate.parse(HEARING_DAY));
            lock.executeQuery().close();
        }
    }

    private int waitingForALock() {
        return jdbc.sql("""
                SELECT count(*) FROM pg_stat_activity
                 WHERE datname = current_database() AND wait_event_type = 'Lock'
                """).query(Integer.class).single();
    }

    private Map<String, Object> share(final UUID shareId) {
        return jdbc.sql("SELECT * FROM hearing_share WHERE share_id = :shareId")
                .param("shareId", shareId).query().singleRow();
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

    private static Instant instant(final Map<String, Object> row, final String column) {
        final Object value = row.get(column);
        return value instanceof OffsetDateTime offset ? offset.toInstant() : ((Timestamp) value).toInstant();
    }

    private Map<String, Object> day() {
        return jdbc.sql("SELECT * FROM hearing_day_head WHERE hearing_id = :hearingId AND hearing_day = :day")
                .param("hearingId", hearingId).param("day", LocalDate.parse(HEARING_DAY))
                .query().singleRow();
    }
}
