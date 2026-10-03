package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.NotShare;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Reading;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Share;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Duplicate;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Stored;
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
 */
public class IntakeService {

    private final ShareIdentityParser parser;

    private final KeyDetailsExtractor extractor;

    private final EventReceipts receipts;

    private final ShareStore shareStore;

    private final IntakeObserver observer;

    /**
     * Creates the service.
     *
     * @param parser     reads the identity
     * @param extractor  reads the key details
     * @param receipts   the receipt port
     * @param shareStore the share port
     * @param observer   the metrics port
     */
    public IntakeService(final ShareIdentityParser parser, final KeyDetailsExtractor extractor,
            final EventReceipts receipts, final ShareStore shareStore, final IntakeObserver observer) {
        this.parser = parser;
        this.extractor = extractor;
        this.receipts = receipts;
        this.shareStore = shareStore;
        this.observer = observer;
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
        // Read before the store transaction opens, so a bad field never holds the lock (FR-021).
        final Projection projection = extractor.extract(share.body());
        final StoreRequest request = new StoreRequest(messageId, identity, identity.shareId(),
                SharedDays.from(identity.sharedAt()), PayloadChecksum.sha256Hex(text), text, projection);
        return switch (counted(IntakeStage.STORE, () -> shareStore.store(request))) {
            case Stored stored -> stored(stored, messageId, identity, projection);
            case Duplicate duplicate -> {
                observer.duplicate();
                yield result(IntakeOutcome.DUPLICATE, messageId, duplicate.existingShareId(), identity);
            }
        };
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
