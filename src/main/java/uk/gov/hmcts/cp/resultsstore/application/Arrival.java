package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.NotShare;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Reading;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Share;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.ReceiptStatus;

/**
 * One delivery of a message, as the receipt records it.
 *
 * @param messageId     the broker's {@code JMSMessageID}, {@code null} when it had none
 * @param deliveryCount the broker's {@code JMSXDeliveryCount}
 * @param text          the message text, {@code null} when it carried none
 * @param reading       what the text was read as
 */
public record Arrival(String messageId, int deliveryCount, String text, Reading reading) {

    /** Prefixes the key of a message that had no message id (research R3). */
    public static final String SYNTHETIC_KEY_PREFIX = "sha256:";

    /**
     * The receipt's key: the broker's message id, or {@code sha256:} and the checksum of the text
     * (of the empty string when there is none) when the message had no id (FR-005).
     */
    public String key() {
        return messageId == null
                ? SYNTHETIC_KEY_PREFIX + PayloadChecksum.sha256Hex(text == null ? "" : text)
                : messageId;
    }

    /** The status a first arrival is written with: {@code RECEIVED} for a share, else its end state. */
    public ReceiptStatus status() {
        return switch (reading) {
            case Share _ -> ReceiptStatus.RECEIVED;
            case NotShare notShare -> notShare.reason().status();
        };
    }

    /** {@code hearing.id}, if it was read. */
    public UUID hearingId() {
        return switch (reading) {
            case Share share -> share.identity().hearingId();
            case NotShare notShare -> notShare.hearingId();
        };
    }

    /** {@code hearingDay}, if it was read. */
    public LocalDate hearingDay() {
        return switch (reading) {
            case Share share -> share.identity().hearingDay();
            case NotShare notShare -> notShare.hearingDay();
        };
    }

    /** {@code sharedTime}, if it was read. */
    public Instant sharedAt() {
        return switch (reading) {
            case Share share -> share.identity().sharedAt();
            case NotShare notShare -> notShare.sharedAt();
        };
    }

    /** The bounded reason a non-share is recorded with; {@code null} for a share. */
    public String reason() {
        return switch (reading) {
            case Share _ -> null;
            case NotShare notShare -> notShare.reason().name();
        };
    }

    /**
     * The text the receipt keeps: a non-share's text (FR-008, FR-009), except one holding a raw
     * U+0000, which no PostgreSQL {@code text} column can hold (research R8); {@code null} for a share,
     * whose text goes to the payload table.
     */
    public String keptText() {
        return switch (reading) {
            case Share _ -> null;
            case NotShare notShare -> notShare.reason() == NonShareReason.NUL_CHARACTER ? null : text;
        };
    }
}
