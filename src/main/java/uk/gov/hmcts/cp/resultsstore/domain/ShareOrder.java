package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/**
 * Whether a stored share arrived in order: the {@code order} tag of {@code resultsstore.intake.stored}
 * and {@code resultsstore.intake.lag}.
 */
public enum ShareOrder {

    /** No later share of the day was stored before it. */
    IN_ORDER,
    /** A later share of the day was stored first (FR-024). */
    OUT_OF_ORDER;

    /**
     * The order a store result names.
     *
     * @param outOfOrder whether a later share of the day was stored first
     * @return the order
     */
    public static ShareOrder from(final boolean outOfOrder) {
        return outOfOrder ? OUT_OF_ORDER : IN_ORDER;
    }

    /** The {@code order} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
