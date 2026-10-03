package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

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
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.application.SweepCandidate;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;

/**
 * The extraction sweep on PostgreSQL (FR-033 to FR-037, SC-006, US5): it selects only {@code FAILED}
 * rows due a retry, re-reads the stored text, fills the key details and defendant rows under the
 * hearing-day lock and the share row's lock, records a renewed failure, stops retrying an unexpected
 * failure at the limit, and two sweeps at once work each row once.
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
