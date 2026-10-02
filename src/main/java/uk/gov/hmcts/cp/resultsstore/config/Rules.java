package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;

/** The checks the settings records run when they are bound; each failure names the property. */
final class Rules {

    private Rules() {
        // Static functions only.
    }

    /* default */ static void within(final String name, final Duration value, final Duration least,
            final Duration most) {
        if (value.compareTo(least) < 0 || value.compareTo(most) > 0) {
            throw new IllegalArgumentException(name + " must be from " + least + " to " + most);
        }
    }

    /* default */ static void within(final String name, final int value, final int least, final int most) {
        if (value < least || value > most) {
            throw new IllegalArgumentException(name + " must be from " + least + " to " + most);
        }
    }

    /* default */ static void atLeast(final String name, final Duration value, final Duration least) {
        if (value.compareTo(least) < 0) {
            throw new IllegalArgumentException(name + " must be at least " + least);
        }
    }

    /* default */ static void positive(final String name, final Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be above zero");
        }
    }

    /* default */ static void atMost(final String name, final Duration value, final String boundName,
            final Duration bound) {
        if (value.compareTo(bound) > 0) {
            throw new IllegalArgumentException(name + " must not exceed " + boundName);
        }
    }
}
