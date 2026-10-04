package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/**
 * What one court application's lookup came to: the {@code outcome} tag of
 * {@code resultsstore.enrichment.applications} (contracts/metrics.md).
 */
public enum ApplicationLookupOutcome {

    /** Progression had the application {@code FINALISED} with results; they were copied. */
    ENRICHED,
    /** Progression answered {@code 200 {}}, or {@code courtApplication} was missing or {@code null}. */
    NOT_FOUND,
    /** The application's status was missing, not a string, or not {@code FINALISED}. */
    NOT_FINALISED,
    /** {@code FINALISED} with {@code judicialResults} missing, {@code null} or empty. */
    NO_RESULTS,
    /** The application's id was missing or not a canonical UUID, so no lookup was made. */
    INVALID_ID;

    /** The {@code outcome} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
