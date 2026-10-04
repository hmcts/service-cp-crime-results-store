package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/** How one delivery ended, once acknowledged. */
public enum IntakeOutcome {

    /** A new share was stored. */
    STORED,
    /** A share already stored under the same identity; nothing new was stored. */
    DUPLICATE,
    /** Not a share; recorded on its receipt with a bounded reason. */
    NOT_A_SHARE,
    /** A redelivery whose receipt was already in an end state; no further work (FR-004). */
    ALREADY_SETTLED;

    /** The bounded value this outcome is logged and tagged with. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
