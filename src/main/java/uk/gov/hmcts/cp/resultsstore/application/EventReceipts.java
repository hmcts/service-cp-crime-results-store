package uk.gov.hmcts.cp.resultsstore.application;

/**
 * The receipt of every message, keyed by the broker's message id (FR-002 to FR-005).
 *
 * <p>An arrival is written and committed in its own short transaction, before any store work, so
 * its attempt count survives a store transaction that rolls back (research R1).
 */
public interface EventReceipts {

    /**
     * Records one delivery: a first arrival inserts the receipt ({@code RECEIVED}, or straight into
     * its end state for a non-share); a later one raises the attempt count and the delivery details,
     * and changes the status, reason and text only while the receipt is still {@code RECEIVED}.
     *
     * @param arrival the delivery
     * @return the receipt as it stands after this delivery, committed
     */
    ReceiptState recordArrival(Arrival arrival);
}
