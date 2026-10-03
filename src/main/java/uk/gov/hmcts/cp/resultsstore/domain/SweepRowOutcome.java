package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/** How the extraction sweep finished one row: the {@code outcome} tag of {@code resultsstore.sweep.rows}. */
public enum SweepRowOutcome {

    /** The key details were read; the row is now {@code OK}. */
    FIXED,
    /** The key details still could not be read; the new reason, version and attempts are recorded. */
    FAILED_AGAIN,
    /** Under the locks the row was no longer {@code FAILED} as selected: another sweep changed it first. */
    SKIPPED,
    /** The row's work threw; nothing was written for it and the round went on. */
    ERROR;

    /** The {@code outcome} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
