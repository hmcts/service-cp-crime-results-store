package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Locale;

/**
 * Why the key details could not be read. The constant's name prefixes the bounded
 * {@code projection_reason}; its lower-case name is the {@code kind} metric tag.
 */
public enum ExtractionFailureKind {

    /** A required id is absent or null. */
    MISSING,
    /** A value is present but of the wrong JSON type. */
    WRONG_TYPE,
    /** A string that should be an id is not a canonical UUID. */
    INVALID_UUID,
    /**
     * A string key detail holds U+0000 (sent as the six-character JSON escape for it), which a
     * PostgreSQL {@code text} column cannot hold (research R8).
     */
    NUL_CHARACTER,
    /** Anything else thrown while reading. */
    UNEXPECTED;

    /** The {@code kind} tag of {@code resultsstore.extraction.failed}. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
