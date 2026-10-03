package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/** The transaction an intake attempt failed in: the {@code stage} tag of {@code resultsstore.intake.failed}. */
public enum IntakeStage {

    /** The receipt's own short transaction. */
    RECEIPT,
    /** The store transaction. */
    STORE,
    /** The enrichment step between the two transactions: the progression lookups (spec 002). */
    ENRICH;

    /** The {@code stage} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
