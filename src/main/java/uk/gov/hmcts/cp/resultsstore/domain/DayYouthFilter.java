package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The {@code dayYouthSeen} filter of pull and search (FR-011, FR-026). Its wire values are case-sensitive.
 */
public enum DayYouthFilter {

    /** No filter: the parameter is absent. */
    ANY(null),
    /** {@code notFalse}: days whose flag is {@code true} or unknown. */
    NOT_FALSE("notFalse"),
    /** {@code true}: days whose flag is {@code true}. */
    TRUE("true"),
    /** {@code false}: days whose flag is {@code false}; search only. */
    FALSE("false");

    private final String wire;

    DayYouthFilter(final String wireValue) {
        this.wire = wireValue;
    }

    /**
     * Reads a wire value.
     *
     * @param value the parameter's value, possibly {@code null}
     * @return the filter, or empty for anything but {@code notFalse}, {@code true} and {@code false}
     */
    public static Optional<DayYouthFilter> fromValue(final String value) {
        return Arrays.stream(values())
                .filter(filter -> filter.wire != null && filter.wire.equals(value))
                .findFirst();
    }

    /** The wire value; {@code null} for {@link #ANY}. */
    public String wireValue() {
        return wire;
    }

    /** Whether pull accepts the filter: every one but {@link #FALSE}. */
    public boolean allowedOnPull() {
        return this != FALSE;
    }
}
