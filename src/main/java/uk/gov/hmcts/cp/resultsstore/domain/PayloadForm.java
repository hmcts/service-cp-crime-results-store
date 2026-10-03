package uk.gov.hmcts.cp.resultsstore.domain;

/**
 * Which stored form a served payload was read from: the {@code Results-Store-Payload-Form} header
 * (contracts/read-api.md §4.4).
 */
public enum PayloadForm {

    /** {@code payload_json} without {@code _metadata}, as the database writes it. */
    WORKING_COPY("working-copy"),
    /** {@code payload_text} without {@code _metadata}, written back by the service. */
    ARRIVED_TEXT("arrived-text");

    private final String header;

    PayloadForm(final String headerValue) {
        this.header = headerValue;
    }

    /** The header's value. */
    public String headerValue() {
        return header;
    }
}
