package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/** How the extraction sweep finished one row: the {@code outcome} tag of {@code resultsstore.sweep.rows}. */
public enum SweepRowOutcome {

    /** The key details were read; the row is now {@code OK}. */
    FIXED,
    /**
     * The key details still could not be read, or the row's work threw ({@code UNEXPECTED:<class>}); the
     * new reason, version and attempts are recorded.
     */
    FAILED_AGAIN,
    /** Under the locks the row was no longer {@code FAILED} as selected: another sweep changed it first. */
    SKIPPED,
    /**
     * The row's work threw, and so did recording it as a failed attempt; nothing was written for it and
     * the round went on.
     */
    ERROR,
    /**
     * The row's work was cut short because the sweep is stopping (its thread interrupted, or a
     * transaction not opened once stop was asked); nothing was written for it and no attempt was spent.
     */
    CANCELLED;

    /** The {@code outcome} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
