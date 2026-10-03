package uk.gov.hmcts.cp.resultsstore.application;

import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;

/**
 * Retries shares whose key details could not be read (FR-033 to FR-037, research R13).
 *
 * <p>A round selects the {@code FAILED} rows due a retry, without locks. For each it reads the stored
 * payload text (never the parsed copy) as JSON and extracts, outside any transaction, then hands the result to
 * the store, which writes it under the hearing-day lock and the share row's lock only if the row is
 * still {@code FAILED} with the attempts it had when selected. That re-check, not a distributed lock,
 * keeps several pods sweeping at once correct: each row is worked at most once per round. A row whose
 * work throws is recorded as a failed {@code UNEXPECTED} attempt, or counted {@code error} when even
 * that cannot be written, and the round goes on. Every outcome is reported after the row's
 * transaction ends.
 */
public class ExtractionSweep {

    private static final Logger LOG = LoggerFactory.getLogger(ExtractionSweep.class);

    private final ShareStore store;

    private final ShareIdentityParser parser;

    private final KeyDetailsExtractor extractor;

    private final IntakeObserver observer;

    private final Settings settings;

    /**
     * Creates the sweep.
     *
     * @param store     the share tables
     * @param parser    reads the stored text back as JSON
     * @param extractor reads the key details
     * @param observer  the metrics port
     * @param settings  the version, retry limit and batch size of a round
     */
    public ExtractionSweep(final ShareStore store, final ShareIdentityParser parser,
            final KeyDetailsExtractor extractor, final IntakeObserver observer, final Settings settings) {
        this.store = store;
        this.parser = parser;
        this.extractor = extractor;
        this.observer = observer;
        this.settings = settings;
    }

    /**
     * Runs one round. An interrupt (the schedule stopping) ends it before its next row.
     *
     * @return each selected row's outcome, in selection order
     */
    public List<SweepRowOutcome> runRound() {
        final List<SweepRowOutcome> outcomes = store
                .sweepCandidates(settings.extractorVersion(), settings.maxAttempts(), settings.batchSize())
                .stream()
                // Stopping: the rows not yet started are left for the next round, on this pod or another.
                .takeWhile(candidate -> !Thread.currentThread().isInterrupted())
                .map(this::sweepRow)
                .toList();
        if (!outcomes.isEmpty()) {
            LOG.info("Extraction sweep round finished; rows={} fixed={} failedAgain={} skipped={} error={} "
                    + "cancelled={}", outcomes.size(), count(outcomes, SweepRowOutcome.FIXED),
                    count(outcomes, SweepRowOutcome.FAILED_AGAIN), count(outcomes, SweepRowOutcome.SKIPPED),
                    count(outcomes, SweepRowOutcome.ERROR), count(outcomes, SweepRowOutcome.CANCELLED));
        }
        return outcomes;
    }

    /**
     * One row. A runtime failure, reading or writing, is logged by its class alone (its message may
     * quote the row) and recorded as a failed attempt, {@code UNEXPECTED:<class>}, in a second
     * transaction under the same locks and re-check: the attempt count then grows, so a row that fails
     * the same way every round stops being selected at the retry limit (FR-035) instead of holding the
     * head of every batch. Only when that write fails too is the row
     * counted {@code error}, with nothing written, and the round goes on (FR-037); a failure met while
     * the thread is interrupted (the schedule stopping) is counted {@code cancelled} instead.
     */
    // Catch-to-record: the failure becomes an explicit outcome with a bounded tag and is logged by class.
    // Errors are not caught.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private SweepRowOutcome sweepRow(final SweepCandidate candidate) {
        Projection projection;
        SweepRowOutcome outcome;
        try {
            // JSON alone: the identity was proved when stored, and its rules may have been tightened since.
            projection = extractor.extract(parser.readTree(store.payloadText(candidate.shareId())));
            outcome = store.recordReextraction(candidate, projection, settings.extractorVersion());
        } catch (final RuntimeException failure) {
            LOG.warn("Extraction sweep could not finish a row; recording it as a failed attempt. shareId={} "
                    + "exception={}", candidate.shareId(), failure.getClass().getName());
            projection = KeyDetailsExtractor.unexpected(failure);
            // A failure met while stopping is the stop's, not the row's: no attempt is spent on it.
            outcome = Thread.currentThread().isInterrupted()
                    ? SweepRowOutcome.CANCELLED
                    : recordFailedAttempt(candidate, projection);
        }
        observer.sweepRow(outcome);
        if (projection instanceof Projection.Failed failed && outcome == SweepRowOutcome.FAILED_AGAIN) {
            observer.extractionFailed(ExtractionStage.SWEEP, failed.kind());
        }
        return outcome;
    }

    // Catch-to-count: the row is left as it was, counted error and logged by class.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private SweepRowOutcome recordFailedAttempt(final SweepCandidate candidate, final Projection failed) {
        SweepRowOutcome outcome;
        try {
            outcome = store.recordReextraction(candidate, failed, settings.extractorVersion());
        } catch (final RuntimeException failure) {
            LOG.warn("Extraction sweep could not record a failed attempt; the row stays as it was for the next "
                    + "round. shareId={} exception={}", candidate.shareId(), failure.getClass().getName());
            outcome = SweepRowOutcome.ERROR;
        }
        return outcome;
    }

    private static int count(final List<SweepRowOutcome> outcomes, final SweepRowOutcome outcome) {
        return Collections.frequency(outcomes, outcome);
    }

    /**
     * What one round works with.
     *
     * @param extractorVersion the extractor version now running ({@link KeyDetailsExtractor#EXTRACTOR_VERSION})
     * @param maxAttempts      the attempts an unexpected failure gets, the intake's included
     * @param batchSize        the most rows one round takes
     */
    public record Settings(int extractorVersion, int maxAttempts, int batchSize) {
    }
}
