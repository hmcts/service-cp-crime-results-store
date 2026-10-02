package uk.gov.hmcts.cp.resultsstore.domain;

/** The status of a receipt ({@code event_receipt.status}). */
public enum ReceiptStatus {
    RECEIVED, STORED, DUPLICATE, UNREADABLE, NO_IDENTITY
}
