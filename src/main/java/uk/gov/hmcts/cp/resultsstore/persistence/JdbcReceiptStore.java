package uk.gov.hmcts.cp.resultsstore.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import uk.gov.hmcts.cp.resultsstore.application.Arrival;
import uk.gov.hmcts.cp.resultsstore.application.EventReceipts;
import uk.gov.hmcts.cp.resultsstore.application.ReceiptState;
import uk.gov.hmcts.cp.resultsstore.domain.ReceiptStatus;

/**
 * The receipt table, {@code event_receipt} (FR-002 to FR-005).
 *
 * <p>{@link #recordArrival} is one upsert in its own transaction. Its {@code SET} raises the attempt
 * count and the delivery details on every delivery, and changes the status, reason, text and settled
 * time only while the stored receipt is still {@code RECEIVED}; {@code RETURNING} always gives the
 * receipt as it now stands. {@link #markStored} and {@link #markDuplicate} run inside the caller's
 * store transaction and act only on a {@code RECEIVED} receipt.
 */
public class JdbcReceiptStore implements EventReceipts {

    private static final String RECORD_ARRIVAL = """
            INSERT INTO event_receipt AS r
                (message_id, status, hearing_id, hearing_day, shared_at, delivery_count, settled_at, reason,
                 message_text)
            VALUES (:messageId, :status, :hearingId, :hearingDay, :sharedAt, :deliveryCount,
                    CASE WHEN :status = 'RECEIVED' THEN NULL ELSE clock_timestamp() END, :reason, :messageText)
            ON CONFLICT (message_id) DO UPDATE SET
                attempts         = r.attempts + 1,
                last_received_at = clock_timestamp(),
                delivery_count   = EXCLUDED.delivery_count,
                status           = CASE WHEN r.status = 'RECEIVED' THEN EXCLUDED.status ELSE r.status END,
                reason           = CASE WHEN r.status = 'RECEIVED' THEN EXCLUDED.reason ELSE r.reason END,
                message_text     = CASE WHEN r.status = 'RECEIVED' THEN EXCLUDED.message_text
                                        ELSE r.message_text END,
                settled_at       = CASE WHEN r.status = 'RECEIVED' THEN EXCLUDED.settled_at
                                        ELSE r.settled_at END
            RETURNING message_id, status, share_id, attempts, (xmax = 0) AS inserted
            """;

    private static final String SETTLE_RECEIVED = """
            UPDATE event_receipt
               SET status = :status, share_id = :shareId, settled_at = clock_timestamp()
             WHERE message_id = :messageId AND status = 'RECEIVED'
            """;

    private static final String MESSAGE_ID = "messageId";

    private static final String STATUS = "status";

    private static final String SHARE_ID = "shareId";

    private final JdbcClient jdbc;

    private final TransactionOperations receiptTransaction;

    /**
     * Creates the store.
     *
     * @param jdbc               the database
     * @param receiptTransaction the receipt's own short transaction
     */
    public JdbcReceiptStore(final JdbcClient jdbc, final TransactionOperations receiptTransaction) {
        this.jdbc = jdbc;
        this.receiptTransaction = receiptTransaction;
    }

    @Override
    public ReceiptState recordArrival(final Arrival arrival) {
        return receiptTransaction.execute(status -> jdbc.sql(RECORD_ARRIVAL)
                .param(MESSAGE_ID, arrival.key())
                .param(STATUS, arrival.status().name())
                .param("hearingId", arrival.hearingId())
                .param("hearingDay", arrival.hearingDay())
                .param("sharedAt", arrival.sharedAt() == null
                        ? null : OffsetDateTime.ofInstant(arrival.sharedAt(), ZoneOffset.UTC))
                .param("deliveryCount", arrival.deliveryCount())
                .param("reason", arrival.reason())
                .param("messageText", arrival.keptText())
                .query((row, rowNumber) -> new ReceiptState(
                        row.getString("message_id"),
                        ReceiptStatus.valueOf(row.getString(STATUS)),
                        row.getObject("share_id", UUID.class),
                        row.getInt("attempts"),
                        row.getBoolean("inserted")))
                .single());
    }

    /**
     * Marks a {@code RECEIVED} receipt {@code STORED}, inside the caller's store transaction.
     *
     * @param messageId the receipt's key
     * @param shareId   the share just stored
     * @return whether the receipt was {@code RECEIVED} and is now marked
     */
    public boolean markStored(final String messageId, final UUID shareId) {
        return settle(messageId, ReceiptStatus.STORED, shareId);
    }

    /**
     * Marks a {@code RECEIVED} receipt {@code DUPLICATE}, inside the caller's store transaction.
     *
     * @param messageId the receipt's key
     * @param shareId   the share already stored under the same identity
     * @return whether the receipt was {@code RECEIVED} and is now marked
     */
    public boolean markDuplicate(final String messageId, final UUID shareId) {
        return settle(messageId, ReceiptStatus.DUPLICATE, shareId);
    }

    private boolean settle(final String messageId, final ReceiptStatus status, final UUID shareId) {
        return jdbc.sql(SETTLE_RECEIVED)
                .param(MESSAGE_ID, messageId)
                .param(STATUS, status.name())
                .param(SHARE_ID, shareId)
                .update() == 1;
    }
}
