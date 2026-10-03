package uk.gov.hmcts.cp.resultsstore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;

/**
 * The extraction sweep's round (FR-033 to FR-037, research R13), over a mocked share store: what it
 * selects, that it re-reads the stored text, what it reports, and that one row's failure is counted
 * and never stops the round.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("extraction sweep")
class ExtractionSweepTest {

    private static final int VERSION = 2;

    private static final int MAX_ATTEMPTS = 3;

    private static final int BATCH = 50;

    private static final String HEARING_DAY = "2026-10-02";

    private static final String SHARED_TIME = "2026-10-02T14:19:50.706Z";

    /** Planted in the stored text; never in a log line. */
    private static final String MARKER = "SWEEP-MARKER-7f3a";

    @Mock
    private ShareStore store;

    @Mock
    private IntakeObserver observer;

    private ExtractionSweep sweep() {
        return new ExtractionSweep(store, new ShareIdentityParser(JsonMapper.builder().build()),
                new KeyDetailsExtractor(), observer, new ExtractionSweep.Settings(VERSION, MAX_ATTEMPTS, BATCH));
    }

    private static SweepCandidate candidate(final int attempts) {
        return new SweepCandidate(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse(HEARING_DAY), attempts);
    }

    private static String readable(final SweepCandidate candidate) {
        return SampleShares.share(candidate.hearingId(), HEARING_DAY, SHARED_TIME, "false", MARKER);
    }

    /** Readable as a share, but its court centre id is not a UUID. */
    private static String unreadableCourtCentre(final SweepCandidate candidate) {
        return readable(candidate).replace(SampleShares.COURT_CENTRE.toString(), MARKER);
    }

    @Test
    void round_should_select_with_the_running_version_the_retry_limit_and_the_batch_size() {
        when(store.sweepCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        final List<SweepRowOutcome> outcomes = sweep().runRound();

        assertThat(outcomes).isEmpty();
        verify(store).sweepCandidates(VERSION, MAX_ATTEMPTS, BATCH);
        verifyNoInteractions(observer);
    }

    @Test
    void readable_row_should_be_recorded_with_the_details_read_from_its_stored_text_and_counted_fixed() {
        final SweepCandidate row = candidate(1);
        when(store.sweepCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(row));
        when(store.payloadText(row.shareId())).thenReturn(readable(row));
        when(store.recordReextraction(eq(row), any(), eq(VERSION))).thenReturn(SweepRowOutcome.FIXED);

        final List<SweepRowOutcome> outcomes = sweep().runRound();

        assertThat(outcomes).containsExactly(SweepRowOutcome.FIXED);
        final ArgumentCaptor<Projection> projection = ArgumentCaptor.forClass(Projection.class);
        final InOrder order = inOrder(store, observer);
        order.verify(store).payloadText(row.shareId());
        order.verify(store).recordReextraction(eq(row), projection.capture(), eq(VERSION));
        order.verify(observer).sweepRow(SweepRowOutcome.FIXED);
        assertThat(projection.getValue()).isInstanceOfSatisfying(Projection.Extracted.class, extracted -> {
            assertThat(extracted.keyDetails().courtCentreId()).isEqualTo(SampleShares.COURT_CENTRE);
            assertThat(extracted.defendants()).hasSize(1);
        });
        verify(observer, never()).extractionFailed(any(), any());
    }

    @Test
    void row_that_fails_again_should_be_counted_with_its_kind_at_the_sweep_stage_after_the_write() {
        final SweepCandidate row = candidate(1);
        when(store.sweepCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(row));
        when(store.payloadText(row.shareId())).thenReturn(unreadableCourtCentre(row));
        when(store.recordReextraction(eq(row), any(), eq(VERSION))).thenReturn(SweepRowOutcome.FAILED_AGAIN);

        final List<SweepRowOutcome> outcomes = sweep().runRound();

        assertThat(outcomes).containsExactly(SweepRowOutcome.FAILED_AGAIN);
        final ArgumentCaptor<Projection> projection = ArgumentCaptor.forClass(Projection.class);
        final InOrder order = inOrder(store, observer);
        order.verify(store).recordReextraction(eq(row), projection.capture(), eq(VERSION));
        order.verify(observer).sweepRow(SweepRowOutcome.FAILED_AGAIN);
        order.verify(observer).extractionFailed(ExtractionStage.SWEEP, ExtractionFailureKind.INVALID_UUID);
        assertThat(projection.getValue()).isEqualTo(
                new Projection.Failed("INVALID_UUID:hearing.courtCentre.id", ExtractionFailureKind.INVALID_UUID));
    }

    @Test
    void row_another_sweep_changed_first_should_be_counted_skipped_and_not_as_an_extraction_failure() {
        final SweepCandidate row = candidate(1);
        when(store.sweepCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(row));
        when(store.payloadText(row.shareId())).thenReturn(unreadableCourtCentre(row));
        when(store.recordReextraction(eq(row), any(), eq(VERSION))).thenReturn(SweepRowOutcome.SKIPPED);

        final List<SweepRowOutcome> outcomes = sweep().runRound();

        assertThat(outcomes).containsExactly(SweepRowOutcome.SKIPPED);
        verify(observer).sweepRow(SweepRowOutcome.SKIPPED);
        verify(observer, never()).extractionFailed(any(), any());
    }

    @Test
    void row_whose_write_throws_should_be_counted_as_an_error_and_the_round_should_go_on() {
        final SweepCandidate failing = candidate(1);
        final SweepCandidate next = candidate(2);
        when(store.sweepCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(failing, next));
        when(store.payloadText(failing.shareId())).thenReturn(readable(failing));
        when(store.payloadText(next.shareId())).thenReturn(readable(next));
        when(store.recordReextraction(eq(failing), any(), eq(VERSION)))
                .thenThrow(new QueryTimeoutException("statement quoted " + MARKER));
        when(store.recordReextraction(eq(next), any(), eq(VERSION))).thenReturn(SweepRowOutcome.FIXED);

        try (CapturedLog log = CapturedLog.forClass(ExtractionSweep.class)) {
            final List<SweepRowOutcome> outcomes = sweep().runRound();

            assertThat(outcomes).containsExactly(SweepRowOutcome.ERROR, SweepRowOutcome.FIXED);
            final InOrder order = inOrder(observer);
            order.verify(observer).sweepRow(SweepRowOutcome.ERROR);
            order.verify(observer).sweepRow(SweepRowOutcome.FIXED);
            assertThat(log.messages()).anySatisfy(line -> assertThat(line)
                    .contains("shareId=" + failing.shareId())
                    .contains(QueryTimeoutException.class.getName()));
            assertThat(log.events()).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            assertThat(String.join("\n", log.messages())).doesNotContain(MARKER);
        }
    }

    @Test
    void row_whose_stored_text_is_no_longer_read_as_a_share_should_be_an_error_with_nothing_written() {
        final SweepCandidate row = candidate(1);
        when(store.sweepCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(row));
        when(store.payloadText(row.shareId())).thenReturn("not json " + MARKER);

        final List<SweepRowOutcome> outcomes = sweep().runRound();

        assertThat(outcomes).containsExactly(SweepRowOutcome.ERROR);
        verify(store, never()).recordReextraction(any(), any(), anyInt());
        verify(observer).sweepRow(SweepRowOutcome.ERROR);
    }

    @Test
    void round_with_rows_should_log_its_counts_and_no_stored_text() {
        final SweepCandidate row = candidate(1);
        when(store.sweepCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(row));
        when(store.payloadText(row.shareId())).thenReturn(readable(row));
        when(store.recordReextraction(eq(row), any(), eq(VERSION))).thenReturn(SweepRowOutcome.FIXED);

        try (CapturedLog log = CapturedLog.forClass(ExtractionSweep.class)) {
            sweep().runRound();

            assertThat(log.messages()).singleElement().satisfies(line -> assertThat(line)
                    .contains("rows=1").contains("fixed=1").contains("failedAgain=0").contains("skipped=0")
                    .contains("error=0").doesNotContain(MARKER));
        }
    }
}
