package uk.gov.hmcts.cp.resultsstore.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.domain.ApplicationLookupOutcome;
import uk.gov.hmcts.cp.resultsstore.domain.EnrichmentSkip;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.ShareOrder;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;

/**
 * The intake's and the sweep's metrics on Micrometer (contracts/metrics.md, research R16), exported to
 * Azure Monitor through the OpenTelemetry starter and exposed at {@code /actuator/prometheus}.
 *
 * <p>Every tag value is a {@code domain} enum's {@code tag()}, so the values are a fixed list and the
 * mapping is measured where it lives; this adapter has no branches. Every meter is registered at start
 * with each of its tag sets, so a dashboard shows a zero rather than no series. The callers report
 * after the commit (or the rollback) they describe.
 */
public class MicrometerIntakeObserver implements IntakeObserver {

    private static final String PREFIX = "resultsstore.";

    private static final String RECEIVED_METER = PREFIX + "intake.received";

    private static final String STORED_METER = PREFIX + "intake.stored";

    private static final String NOT_SHARE_METER = PREFIX + "intake.not.share";

    private static final String DUPLICATE_METER = PREFIX + "intake.duplicate";

    private static final String ALREADY_SETTLED_METER = PREFIX + "intake.already.settled";

    private static final String FAILED_METER = PREFIX + "intake.failed";

    private static final String MESSAGE_ID_MISSING_METER = PREFIX + "intake.message.id.missing";

    private static final String PARSED_COPY_SKIPPED_METER = PREFIX + "intake.parsed.copy.skipped";

    private static final String EXTRACTION_FAILED_METER = PREFIX + "extraction.failed";

    private static final String SWEEP_ROWS_METER = PREFIX + "sweep.rows";

    private static final String SWEEP_ROUNDS_FAILED_METER = PREFIX + "sweep.rounds.failed";

    private static final String LAG_METER = PREFIX + "intake.lag";

    private static final String APPLICATIONS_METER = PREFIX + "enrichment.applications";

    private static final String LOOKUP_METER = PREFIX + "enrichment.lookup";

    private static final String SKIPPED_METER = PREFIX + "enrichment.skipped";

    private static final String APPLIED_METER = PREFIX + "enrichment.applied";

    /** The lookup timer's outcome for a call that ended in a {@code progression_*} cause. */
    private static final String LOOKUP_FAILED = "failed";

    private static final String OUTCOME = "outcome";

    private static final String ORDER = "order";

    private static final String STAGE = "stage";

    private final MeterRegistry registry;

    /**
     * Creates the observer and registers every meter with each of its tag sets.
     *
     * @param registry the application's registry
     */
    public MicrometerIntakeObserver(final MeterRegistry registry) {
        this.registry = registry;
        Arrays.stream(new String[] {RECEIVED_METER, DUPLICATE_METER, ALREADY_SETTLED_METER, MESSAGE_ID_MISSING_METER,
                        PARSED_COPY_SKIPPED_METER, SWEEP_ROUNDS_FAILED_METER, APPLIED_METER})
                .forEach(name -> Counter.builder(name).register(registry));
        Arrays.stream(ShareOrder.values()).forEach(order -> {
            stored(order);
            lag(order);
        });
        Arrays.stream(NonShareReason.values()).forEach(this::notShareCounter);
        Arrays.stream(IntakeStage.values()).forEach(stage -> Arrays.stream(IntakeFailureCause.values())
                .filter(cause -> cause.belongsTo(stage))
                .forEach(cause -> failed(stage, cause)));
        Arrays.stream(ExtractionStage.values()).forEach(stage -> Arrays.stream(ExtractionFailureKind.values())
                .forEach(kind -> extractionFailedCounter(stage, kind)));
        Arrays.stream(SweepRowOutcome.values()).forEach(this::sweepRows);
        Arrays.stream(ApplicationLookupOutcome.values()).forEach(this::applications);
        // No call is made for an invalid id, so it is no timer outcome.
        Arrays.stream(ApplicationLookupOutcome.values()).filter(outcome -> outcome != ApplicationLookupOutcome.INVALID_ID)
                .forEach(outcome -> lookup(outcome.tag()));
        lookup(LOOKUP_FAILED);
        Arrays.stream(EnrichmentSkip.values()).forEach(this::skipped);
    }

    @Override
    public void received() {
        registry.counter(RECEIVED_METER).increment();
    }

    @Override
    public void messageIdMissing() {
        registry.counter(MESSAGE_ID_MISSING_METER).increment();
    }

    @Override
    public void notShare(final NonShareReason reason) {
        notShareCounter(reason).increment();
    }

    @Override
    public void alreadySettled() {
        registry.counter(ALREADY_SETTLED_METER).increment();
    }

    @Override
    public void stored(final boolean outOfOrder, final Duration lag) {
        final ShareOrder order = ShareOrder.from(outOfOrder);
        stored(order).increment();
        // A timer drops a negative duration; a clock ahead of the store's still counts, as zero. Passed in
        // milliseconds: Duration.toNanos (inside Timer.record(Duration)) throws past 292 years, and any
        // four-digit sharedTime year is accepted. The timer saturates such a lag at that bound.
        lag(order).record(Math.max(0L, lag.toMillis()), TimeUnit.MILLISECONDS);
    }

    @Override
    public void duplicate() {
        registry.counter(DUPLICATE_METER).increment();
    }

    @Override
    public void parsedCopySkipped() {
        registry.counter(PARSED_COPY_SKIPPED_METER).increment();
    }

    @Override
    public void extractionFailed(final ExtractionStage stage, final ExtractionFailureKind kind) {
        extractionFailedCounter(stage, kind).increment();
    }

    @Override
    public void intakeFailed(final IntakeStage stage, final IntakeFailureCause cause) {
        failed(stage, cause).increment();
    }

    @Override
    public void sweepRow(final SweepRowOutcome outcome) {
        sweepRows(outcome).increment();
    }

    @Override
    public void sweepRoundFailed() {
        registry.counter(SWEEP_ROUNDS_FAILED_METER).increment();
    }

    @Override
    public void applicationLookedUp(final ApplicationLookupOutcome outcome) {
        applications(outcome).increment();
    }

    @Override
    public void lookupTimed(final Optional<ApplicationLookupOutcome> outcome, final Duration duration) {
        lookup(outcome.map(ApplicationLookupOutcome::tag).orElse(LOOKUP_FAILED)).record(duration);
    }

    @Override
    public void enrichmentSkipped(final EnrichmentSkip reason) {
        skipped(reason).increment();
    }

    @Override
    public void enrichmentApplied() {
        registry.counter(APPLIED_METER).increment();
    }

    private Counter stored(final ShareOrder order) {
        return registry.counter(STORED_METER, ORDER, order.tag());
    }

    private Timer lag(final ShareOrder order) {
        return registry.timer(LAG_METER, ORDER, order.tag());
    }

    private Counter notShareCounter(final NonShareReason reason) {
        return registry.counter(NOT_SHARE_METER, "status", reason.statusTag(), "reason", reason.tag());
    }

    private Counter failed(final IntakeStage stage, final IntakeFailureCause cause) {
        return registry.counter(FAILED_METER, STAGE, stage.tag(), "cause", cause.tag());
    }

    private Counter extractionFailedCounter(final ExtractionStage stage, final ExtractionFailureKind kind) {
        return registry.counter(EXTRACTION_FAILED_METER, STAGE, stage.tag(), "kind", kind.tag());
    }

    private Counter sweepRows(final SweepRowOutcome outcome) {
        return registry.counter(SWEEP_ROWS_METER, OUTCOME, outcome.tag());
    }

    private Counter applications(final ApplicationLookupOutcome outcome) {
        return registry.counter(APPLICATIONS_METER, OUTCOME, outcome.tag());
    }

    private Timer lookup(final String outcome) {
        return registry.timer(LOOKUP_METER, OUTCOME, outcome);
    }

    private Counter skipped(final EnrichmentSkip reason) {
        return registry.counter(SKIPPED_METER, "reason", reason.tag());
    }
}
