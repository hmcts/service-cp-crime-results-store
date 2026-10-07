package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/**
 * Why a share needing lookups was stored without them: the {@code reason} tag of
 * {@code resultsstore.enrichment.skipped} (contracts/metrics.md).
 */
public enum EnrichmentSkip {

    /** Enrichment is switched off. */
    DISABLED,
    /** The share was already stored, so its lookups were not made again. */
    ALREADY_STORED;

    /** The {@code reason} tag. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
