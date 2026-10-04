package uk.gov.hmcts.cp.resultsstore.application;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;

/**
 * Retries shares whose key details could not be read (FR-033 to FR-037, research R13).
 *
 * <p>A round selects the {@code FAILED} rows due a retry, without locks, never tried first, then the
 * longest since tried. For each it reads the share's working copy ({@code payload_json}, or the
 * arrived text when there is none: specs/002-enrichment FR-033) as JSON and extracts, outside any
 * transaction, then hands the result to the store, which writes it under the
 * hearing-day lock and the share row's lock only if the row is still {@code FAILED} with the attempts
 * it had when selected. That re-check, not a distributed lock, keeps several pods sweeping at once
 * correct: each row is worked at most once per round. A row whose stored payload is missing, or whose
 * parsing or extraction throws, is recorded as a failed {@code UNEXPECTED} attempt; a row whose
 * database read or write fails is an operational {@code error} and its projection is left alone. Each try is then recorded in its own transaction,
 * and the round goes on. Every outcome is reported after the row's transactions end.
 */
public class ExtractionSweep {

    private static final Logger LOG = LoggerFactory.getLogger(ExtractionSweep.class);

    private final ShareStore store;

    private final ShareIdentityParser parser;

    private final KeyDetailsExtractor extractor;

    private final IntakeObserver observer;

    private final Settings settings;

    /** Set by {@link #stop()}; read by the round's thread before each transaction it would open. */
    private volatile boolean stopping;

    /**
     * Creates the sweep.
     *
     * @param store     the share tables
     * @param parser    reads the working copy back as JSON
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

    /** Lets rounds work their rows again after a {@link #stop()}. */
    public void start() {
        stopping = false;
    }

    /**
     * Asks the sweep to stop: from now on no transaction is opened for a row, neither its write nor
     * the record of its try, and no further row is started. A row already inside a transaction runs
     * to that transaction's end. Called by the schedule before it interrupts and waits.
     */
    public void stop() {
        stopping = true;
    }

    /**
     * Runs one round. A stop, or an interrupt, ends it before its next row.
     *
     * @return each selected row's outcome, in selection order
     */
    public List<SweepRowOutcome> runRound() {
        final List<SweepRowOutcome> outcomes = store
                .sweepCandidates(settings.extractorVersion(), settings.maxAttempts(), settings.batchSize())
                .stream()
                // Stopping: the rows not yet started are left for the next round, on this pod or another.
                .takeWhile(candidate -> !isStopping())
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
     * One row, in two steps. READ+EXTRACT (the working copy, read as JSON, then the extractor): the
     * database failing the read is operational, counted {@code error} with the projection left alone,
     * as for a failed write; any other runtime failure here (a missing payload row, unreadable JSON,
     * the extractor) is the row's own, logged by class alone (its message may quote the row) and
     * recorded as a failed attempt, {@code UNEXPECTED:<class>}, so a row that fails the same way every
     * round stops at the retry limit (FR-035). WRITE (the store transaction): a runtime failure here is
     * operational, not the row's; nothing about the projection changes and the row is counted
     * {@code error} (FR-037). Either way the try is then recorded on its own ({@link #recordTried}), so
     * a row that keeps failing rotates behind the others. A row met while the sweep is stopping
     * ({@link #stop()}, or the thread interrupted) is {@code cancelled}: no transaction is opened for it.
     */
    private SweepRowOutcome sweepRow(final SweepCandidate candidate) {
        Projection projection = null;
        SweepRowOutcome outcome;
        try {
            projection = readAndExtract(candidate);
            // Checked immediately before the write's transaction would open.
            outcome = isStopping() ? SweepRowOutcome.CANCELLED : write(candidate, projection);
        } catch (final RetryableIntakeException failure) {
            // The database failed reading the working copy: operational, like a failed write.
            LOG.warn("Extraction sweep could not read a row's stored payload; an operational error, so the row is "
                    + "left as it was. shareId={} cause={} exception={}", candidate.shareId(),
                    failure.getFailureCause(), Objects.requireNonNullElse(failure.getCause(), failure).getClass()
                            .getName());
            outcome = isStopping() ? SweepRowOutcome.CANCELLED : SweepRowOutcome.ERROR;
        }
        recordTried(candidate);
        observer.sweepRow(outcome);
        if (projection instanceof Projection.Failed failed && outcome == SweepRowOutcome.FAILED_AGAIN) {
            observer.extractionFailed(ExtractionStage.SWEEP, failed.kind());
        }
        return outcome;
    }

    /**
     * Reads the working copy and extracts. A database failure reading it is thrown, as the
     * store classified it, for the caller to count as operational; any other runtime failure, a
     * missing payload row included, becomes the row's {@code UNEXPECTED} projection.
     *
     * @throws RetryableIntakeException when the database read fails
     */
    // Catch-to-record: the failure becomes the row's UNEXPECTED projection and is logged by class.
    // Errors are not caught.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private Projection readAndExtract(final SweepCandidate candidate) {
        Projection projection;
        try {
            // JSON alone: the identity was proved when stored, and its rules may have been tightened since.
            projection = extractor.extract(parser.readTree(store.payloadForExtraction(candidate.shareId())));
        } catch (final RetryableIntakeException operational) {
            throw operational;
        } catch (final RuntimeException failure) {
            LOG.warn("Extraction sweep could not read a row's key details; recording it as a failed attempt. "
                    + "shareId={} exception={}", candidate.shareId(), failure.getClass().getName());
            projection = KeyDetailsExtractor.unexpected(failure);
        }
        return projection;
    }

    // Catch-to-count: an operational failure leaves the row as it was, counted error and logged by class.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private SweepRowOutcome write(final SweepCandidate candidate, final Projection projection) {
        SweepRowOutcome outcome;
        try {
            outcome = store.recordReextraction(candidate, projection, settings.extractorVersion());
        } catch (final RuntimeException failure) {
            LOG.warn("Extraction sweep could not write a row; an operational error, so the row is left as it "
                    + "was. shareId={} exception={}", candidate.shareId(), failure.getClass().getName());
            // A failure met while stopping is the stop's, not the row's.
            outcome = isStopping() ? SweepRowOutcome.CANCELLED : SweepRowOutcome.ERROR;
        }
        return outcome;
    }

    /**
     * Records the try in its own short transaction, unless the sweep is stopping (checked immediately
     * before that transaction would open). When that fails too, the row stays as it is and the round
     * goes on.
     */
    // Catch-to-log: the try's stamp is best effort; the failure is logged by class.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void recordTried(final SweepCandidate candidate) {
        if (!isStopping()) {
            try {
                store.recordSweepAttempt(candidate.shareId());
            } catch (final RuntimeException failure) {
                LOG.warn("Extraction sweep could not record that it tried a row; the row stays as it is. "
                        + "shareId={} exception={}", candidate.shareId(), failure.getClass().getName());
            }
        }
    }

    /** Asked to stop, or the thread interrupted (the schedule stopping it). */
    private boolean isStopping() {
        return stopping || Thread.currentThread().isInterrupted();
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
