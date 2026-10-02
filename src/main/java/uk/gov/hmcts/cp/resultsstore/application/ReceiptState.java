package uk.gov.hmcts.cp.resultsstore.application;

import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.domain.ReceiptStatus;

/**
 * A receipt as it stands after a delivery was recorded.
 *
 * @param messageId the receipt's key: the broker's message id, or {@code sha256:<hex>} when it had none
 * @param status    the current status
 * @param shareId   the stored share ({@code STORED}) or the existing one ({@code DUPLICATE}), else {@code null}
 * @param attempts  deliveries recorded so far, this one included
 * @param inserted  whether this delivery wrote the receipt for the first time
 */
public record ReceiptState(String messageId, ReceiptStatus status, UUID shareId, int attempts, boolean inserted) {

    /** Whether the receipt is in an end state, so a delivery needs no further work (FR-004). */
    public boolean isSettled() {
        return status != ReceiptStatus.RECEIVED;
    }
}
