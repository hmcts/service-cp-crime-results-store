package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/**
 * Why a message is not a share (research R7, R8). The constant's name is the bounded code written
 * to the receipt's {@code reason}; its lower-case name is the metric tag.
 */
public enum NonShareReason {

    /** The message is not a {@code TextMessage}. A {@code TextMessage} with no text is {@link #NOT_JSON}. */
    NOT_TEXT_MESSAGE(ReceiptStatus.UNREADABLE),
    /** The text holds U+0000, which PostgreSQL cannot store in any {@code text} column. */
    NUL_CHARACTER(ReceiptStatus.UNREADABLE),
    /** Not JSON (including no text or blank text), or JSON with content after the first value. */
    NOT_JSON(ReceiptStatus.UNREADABLE),
    /** JSON, but not an object. */
    NOT_OBJECT(ReceiptStatus.UNREADABLE),
    MISSING_HEARING_ID(ReceiptStatus.NO_IDENTITY),
    INVALID_HEARING_ID(ReceiptStatus.NO_IDENTITY),
    MISSING_HEARING_DAY(ReceiptStatus.NO_IDENTITY),
    INVALID_HEARING_DAY(ReceiptStatus.NO_IDENTITY),
    MISSING_SHARED_TIME(ReceiptStatus.NO_IDENTITY),
    INVALID_SHARED_TIME(ReceiptStatus.NO_IDENTITY);

    private final ReceiptStatus receiptStatus;

    NonShareReason(final ReceiptStatus receiptStatus) {
        this.receiptStatus = receiptStatus;
    }

    /** The end state the receipt is written in. */
    public ReceiptStatus status() {
        return receiptStatus;
    }

    /** The {@code reason} tag of {@code resultsstore.intake.not.share}. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The {@code status} tag of {@code resultsstore.intake.not.share}. */
    public String statusTag() {
        return receiptStatus.name().toLowerCase(Locale.ROOT);
    }
}
