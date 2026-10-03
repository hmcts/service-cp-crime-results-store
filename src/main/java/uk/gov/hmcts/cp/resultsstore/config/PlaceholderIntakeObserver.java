package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;

/**
 * Stands in for the Micrometer observer until it lands (T013, which replaces this class), so the
 * service starts with the subscription enabled. It records nothing, and says so once at start.
 */
public class PlaceholderIntakeObserver implements IntakeObserver {

    private static final Logger LOG = LoggerFactory.getLogger(PlaceholderIntakeObserver.class);

    /** Logs that intake metrics are not recorded. */
    public PlaceholderIntakeObserver() {
        LOG.warn("Intake metrics are not recorded: the placeholder observer drops every intake event until "
                + "the Micrometer observer replaces it");
    }

    @Override
    public void received() {
        // Not recorded until the Micrometer observer replaces this class.
    }

    @Override
    public void messageIdMissing() {
        // Not recorded until the Micrometer observer replaces this class.
    }

    @Override
    public void notShare(final NonShareReason reason) {
        // Not recorded until the Micrometer observer replaces this class.
    }

    @Override
    public void alreadySettled() {
        // Not recorded until the Micrometer observer replaces this class.
    }

    @Override
    public void stored(final boolean outOfOrder, final Duration lag) {
        // Not recorded until the Micrometer observer replaces this class.
    }

    @Override
    public void duplicate() {
        // Not recorded until the Micrometer observer replaces this class.
    }

    @Override
    public void parsedCopySkipped() {
        // Not recorded until the Micrometer observer replaces this class.
    }

    @Override
    public void extractionFailed(final ExtractionStage stage, final ExtractionFailureKind kind) {
        // Not recorded until the Micrometer observer replaces this class.
    }

    @Override
    public void sweepRow(final SweepRowOutcome outcome) {
        // Not recorded until the Micrometer observer replaces this class.
    }

    @Override
    public void intakeFailed(final IntakeStage stage, final IntakeFailureCause cause) {
        // Not recorded until the Micrometer observer replaces this class.
    }
}
