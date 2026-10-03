package uk.gov.hmcts.cp.resultsstore.application;

import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Reading;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Share;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;

/**
 * Retries shares whose key details could not be read (FR-033 to FR-037, research R13).
 *
 * <p>A round selects the {@code FAILED} rows due a retry, without locks. For each it reads the stored
 * payload text (never the parsed copy) and extracts, outside any transaction, then hands the result to
 * the store, which writes it under the hearing-day lock and the share row's lock only if the row is
 * still {@code FAILED} with the attempts it had when selected. That re-check, not a distributed lock,
 * keeps several pods sweeping at once correct: each row is worked at most once per round. A row whose
 * work throws is counted {@code error} and the round goes on. Every outcome is reported after the
 * row's transaction ends.
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
     * @param parser    reads the stored text back into its parsed body
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
     * Runs one round.
     *
     * @return each selected row's outcome, in selection order
     */
    public List<SweepRowOutcome> runRound() {
        final List<SweepRowOutcome> outcomes = store
                .sweepCandidates(settings.extractorVersion(), settings.maxAttempts(), settings.batchSize())
                .stream()
                .map(this::sweepRow)
                .toList();
        if (!outcomes.isEmpty()) {
            LOG.info("Extraction sweep round finished; rows={} fixed={} failedAgain={} skipped={} error={}",
                    outcomes.size(), count(outcomes, SweepRowOutcome.FIXED),
                    count(outcomes, SweepRowOutcome.FAILED_AGAIN), count(outcomes, SweepRowOutcome.SKIPPED),
                    count(outcomes, SweepRowOutcome.ERROR));
        }
        return outcomes;
    }

    /**
     * One row. Any runtime failure, reading or writing, is counted as the row's {@code error} and
     * logged by its class alone (its message may quote the row), and the round goes on (FR-037).
     */
    // Catch-to-count: the failure is recorded as an explicit outcome with a bounded tag and logged;
    // the row stays FAILED for the next round. Errors are not caught.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private SweepRowOutcome sweepRow(final SweepCandidate candidate) {
        Projection projection = null;
        SweepRowOutcome outcome;
        try {
            projection = extractor.extract(body(store.payloadText(candidate.shareId())));
            outcome = store.recordReextraction(candidate, projection, settings.extractorVersion());
        } catch (final RuntimeException failure) {
            LOG.warn("Extraction sweep could not finish a row; it stays FAILED for the next round. shareId={} "
                    + "exception={}", candidate.shareId(), failure.getClass().getName());
            outcome = SweepRowOutcome.ERROR;
        }
        observer.sweepRow(outcome);
        if (projection instanceof Projection.Failed failed && outcome == SweepRowOutcome.FAILED_AGAIN) {
            observer.extractionFailed(ExtractionStage.SWEEP, failed.kind());
        }
        return outcome;
    }

    /** The stored text's parsed body. It was read as a share when stored, so anything else is a fault. */
    private JsonNode body(final String text) {
        final Reading reading = parser.read(text);
        if (!(reading instanceof Share share)) {
            throw new IllegalStateException("the stored payload is no longer read as a share");
        }
        return share.body();
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
