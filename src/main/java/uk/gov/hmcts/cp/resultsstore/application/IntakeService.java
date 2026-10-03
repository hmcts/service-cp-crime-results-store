package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import uk.gov.hmcts.cp.resultsstore.application.ApplicationResultsEnricher.Scan;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.NotShare;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Reading;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Share;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Duplicate;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.EnrichedCopyRefused;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Stored;
import uk.gov.hmcts.cp.resultsstore.domain.ApplicationLookupOutcome;
import uk.gov.hmcts.cp.resultsstore.domain.EnrichmentSkip;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeOutcome;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.ShareIdentity;
import uk.gov.hmcts.cp.resultsstore.domain.SharedDays;

/**
 * Intake of one delivery (research R1): read the identity; record the receipt in its own transaction;
 * stop for a non-share or a redelivery whose receipt is already settled; otherwise read the key
 * details, outside any transaction, and store the share in one transaction. Each port call returns
 * after its commit, so every report to the observer follows the commit it describes. A failed
 * transaction is counted once and its exception rethrown unchanged, so the listener pauses for the
 * capped delay and rolls the message back to the broker, whatever the failure's class: a
 * {@link RetryableIntakeException} and any other runtime failure alike.
 *
 * <p>Enrichment (specs/002-enrichment research R2) runs after the settled check and before the key
 * details are read, with no transaction open: the applications needing results are found; if there
 * are any and enrichment is on, a read-only check finds whether the share is already stored, and if not,
 * progression is asked about each, one at a time, in array order. Any lookup failure fails the attempt,
 * so a share is never stored half-enriched. The key details are read from the working copy. If the
 * store refuses the enriched copy, the store transaction runs once more with the arrived copy.
 */
public class IntakeService {

    private final ShareIdentityParser parser;

    private final KeyDetailsExtractor extractor;

    private final EventReceipts receipts;

    private final ShareStore shareStore;

    private final IntakeObserver observer;

    private final ApplicationResultsEnricher enricher;

    private final ProgressionApplications progression;

    private final LongSupplier nanoClock;

    /**
     * Creates the service.
     *
     * @param parser     reads the identity
     * @param extractor  reads the key details
     * @param receipts   the receipt port
     * @param shareStore the share port
     * @param observer   the metrics port
     * @param enricher    finds the applications needing results and builds the working copy
     * @param progression progression's application query, or {@code null} when enrichment is off
     * @param nanoClock   a monotonic clock in nanoseconds, for the lookup timer
     */
    public IntakeService(final ShareIdentityParser parser, final KeyDetailsExtractor extractor,
            final EventReceipts receipts, final ShareStore shareStore, final IntakeObserver observer,
            final ApplicationResultsEnricher enricher, final ProgressionApplications progression,
            final LongSupplier nanoClock) {
        this.parser = parser;
        this.extractor = extractor;
        this.receipts = receipts;
        this.shareStore = shareStore;
        this.observer = observer;
        this.enricher = enricher;
        this.progression = progression;
        this.nanoClock = nanoClock;
    }

    /**
     * Whether this service asks progression for missing application results.
     *
     * @return {@code false} when enrichment is off (no progression port)
     */
    public boolean enrichesFromProgression() {
        return progression != null;
    }

    /**
     * Takes in one delivery.
     *
     * @param command the delivery
     * @return how it ended
     * @throws RetryableIntakeException when a transaction failed; the message goes back to the broker
     */
    public IntakeResult receive(final IntakeCommand command) {
        // received() is the listener's, counted before any message field is read.
        if (command.messageId() == null) {
            // Counted for the delivery, whether or not its receipt then commits.
            observer.messageIdMissing();
        }
        final Reading reading = command.textMessage()
                ? parser.read(command.text())
                : NotShare.because(NonShareReason.NOT_TEXT_MESSAGE);
        final Arrival arrival = new Arrival(command.messageId(), command.deliveryCount(), command.text(), reading);
        final ReceiptState receipt = counted(IntakeStage.RECEIPT, () -> receipts.recordArrival(arrival));
        return switch (reading) {
            case NotShare notShare -> notShare(notShare, receipt, arrival);
            case Share share -> receipt.isSettled()
                    ? settled(receipt, share.identity())
                    : store(share, receipt.messageId(), command.text());
        };
    }

    private IntakeResult notShare(final NotShare notShare, final ReceiptState receipt, final Arrival arrival) {
        final IntakeOutcome outcome;
        if (receipt.inserted()) {
            observer.notShare(notShare.reason());
            outcome = IntakeOutcome.NOT_A_SHARE;
        } else {
            observer.alreadySettled();
            outcome = IntakeOutcome.ALREADY_SETTLED;
        }
        return new IntakeResult(outcome, receipt.messageId(), receipt.shareId(), arrival.hearingId(),
                arrival.hearingDay(), arrival.sharedAt());
    }

    private IntakeResult settled(final ReceiptState receipt, final ShareIdentity identity) {
        observer.alreadySettled();
        return result(IntakeOutcome.ALREADY_SETTLED, receipt.messageId(), receipt.shareId(), identity);
    }

    private IntakeResult store(final Share share, final String messageId, final String text) {
        final ShareIdentity identity = share.identity();
        // Outside any transaction: the receipt has committed and the store transaction is not open (FR-001).
        final Enrichment enrichment = enrich(share, text);
        // Read before the store transaction opens, so a bad field never holds the lock (FR-021).
        final Projection projection = extractor.extract(enrichment.tree());
        StoreResult result = counted(IntakeStage.STORE, () -> shareStore.store(request(messageId, identity, text,
                enrichment.parsedCopy(), enrichment.applied(), projection)));
        Projection storedProjection = projection;
        if (result instanceof EnrichedCopyRefused) {
            // Once only: the arrived copy, its own key details and the flag false (FR-019).
            observer.enrichmentSkipped(EnrichmentSkip.UNSTORABLE_RESULTS);
            storedProjection = extractor.extract(share.body());
            final Projection arrived = storedProjection;
            result = counted(IntakeStage.STORE,
                    () -> shareStore.store(request(messageId, identity, text, text, false, arrived)));
        }
        return switch (result) {
            case Stored stored -> stored(stored, messageId, identity, storedProjection);
            case Duplicate duplicate -> {
                observer.duplicate();
                yield result(IntakeOutcome.DUPLICATE, messageId, duplicate.existingShareId(), identity);
            }
            case EnrichedCopyRefused _ -> {
                // The arrived copy was refused as enriched: a defect, never run a third time.
                observer.intakeFailed(IntakeStage.STORE, IntakeFailureCause.OTHER);
                throw new IllegalStateException("the arrived copy was refused as an enriched copy");
            }
        };
    }

    private static StoreRequest request(final String messageId, final ShareIdentity identity, final String text,
            final String parsedCopy, final boolean enrichmentApplied, final Projection projection) {
        return new StoreRequest(messageId, identity, identity.shareId(), SharedDays.from(identity.sharedAt()),
                PayloadChecksum.sha256Hex(text), text, parsedCopy, enrichmentApplied, projection);
    }

    /**
     * The working copy: the arrived body and text unless at least one application received results.
     * A failed existence check is counted at {@code store}; a failed lookup, or any other failure of
     * the step, at {@code enrich}.
     */
    private Enrichment enrich(final Share share, final String text) {
        final Scan scan = enricher.scan(share.body());
        Enrichment enrichment = new Enrichment(share.body(), text, false);
        if (!scan.lookups().isEmpty() && progression == null) {
            observer.enrichmentSkipped(EnrichmentSkip.DISABLED);
        } else if (progression != null) {
            for (int skipped = 0; skipped < scan.invalidIds(); skipped++) {
                observer.applicationLookedUp(ApplicationLookupOutcome.INVALID_ID);
            }
            if (!scan.lookups().isEmpty()) {
                enrichment = enrichUnlessStored(share, text, scan);
            }
        }
        return enrichment;
    }

    private Enrichment enrichUnlessStored(final Share share, final String text, final Scan scan) {
        final Optional<UUID> stored = counted(IntakeStage.STORE, () -> shareStore.storedShareId(share.identity()));
        final Enrichment enrichment;
        if (stored.isPresent()) {
            // The store transaction finds it too and marks the receipt DUPLICATE (FR-006).
            observer.enrichmentSkipped(EnrichmentSkip.ALREADY_STORED);
            enrichment = new Enrichment(share.body(), text, false);
        } else {
            enrichment = counted(IntakeStage.ENRICH, () -> lookUpAndEnrich(share, text, scan));
        }
        return enrichment;
    }

    private Enrichment lookUpAndEnrich(final Share share, final String text, final Scan scan) {
        final Map<UUID, ApplicationAnswer> answers = new LinkedHashMap<>();
        for (final UUID applicationId : scan.lookups()) {
            answers.put(applicationId, lookUp(applicationId));
        }
        return enricher.enrich(text, share.body(), answers);
    }

    /** One call, timed and counted when it ends (contracts/metrics.md, the exception to after-commit). */
    private ApplicationAnswer lookUp(final UUID applicationId) {
        final long started = nanoClock.getAsLong();
        final ApplicationAnswer answer;
        try {
            answer = progression.find(applicationId);
        } catch (final RetryableIntakeException failed) {
            observer.lookupTimed(Optional.empty(), Duration.ofNanos(nanoClock.getAsLong() - started));
            throw failed;
        }
        final Duration took = Duration.ofNanos(nanoClock.getAsLong() - started);
        final ApplicationLookupOutcome outcome = enricher.outcome(answer);
        observer.applicationLookedUp(outcome);
        observer.lookupTimed(Optional.of(outcome), took);
        return answer;
    }

    private IntakeResult stored(final Stored stored, final String messageId, final ShareIdentity identity,
            final Projection projection) {
        final Duration lag = Duration.between(identity.sharedAt(), stored.storedAt());
        // A clock ahead of the store's would give a negative lag; a timer takes none (research R22).
        observer.stored(stored.outOfOrder(), lag.isNegative() ? Duration.ZERO : lag);
        if (stored.parsedCopySkipped()) {
            observer.parsedCopySkipped();
        }
        if (projection instanceof Projection.Failed failed) {
            observer.extractionFailed(ExtractionStage.INTAKE, failed.kind());
        }
        if (stored.enrichmentApplied()) {
            // From the flag actually stored, so the fallback never counts (FR-030).
            observer.enrichmentApplied();
        }
        return result(IntakeOutcome.STORED, messageId, stored.shareId(), identity);
    }

    private static IntakeResult result(final IntakeOutcome outcome, final String messageId,
            final UUID shareId, final ShareIdentity identity) {
        return new IntakeResult(outcome, messageId, shareId, identity.hearingId(), identity.hearingDay(),
                identity.sharedAt());
    }

    /**
     * Runs one transaction's port call; a failure is counted once, after its rollback, and rethrown
     * unchanged. A {@link RetryableIntakeException} carries its own stage and cause; any other
     * runtime failure is not a classified database failure, so it is counted as {@code other} at the
     * stage it happened in (contracts/metrics.md) and still escapes, so the container rolls back.
     */
    // Catch-to-count-then-rethrow: nothing is swallowed, and an unexpected failure still moves the
    // failed counter with a bounded reason (Principle VIII). Errors are not caught.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private <T> T counted(final IntakeStage stage, final Supplier<T> transaction) {
        try {
            return transaction.get();
        } catch (final RetryableIntakeException failure) {
            observer.intakeFailed(failure.getStage(), failure.getFailureCause());
            throw failure;
        } catch (final RuntimeException failure) {
            observer.intakeFailed(stage, IntakeFailureCause.OTHER);
            throw failure;
        }
    }
}
