package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/** Where key details failed to be read: the {@code stage} tag of {@code resultsstore.extraction.failed}. */
public enum ExtractionStage {

    /** At intake, before the store transaction. */
    INTAKE,
    /** In the extraction sweep, retrying a {@code FAILED} row. */
    SWEEP;

    /** The {@code stage} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
