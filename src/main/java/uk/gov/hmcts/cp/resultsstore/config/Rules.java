package uk.gov.hmcts.cp.resultsstore.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import uk.gov.hmcts.cp.resultsstore.domain.CanonicalUuid;

/** The checks the settings records run when they are bound; each failure names the property. */
final class Rules {

    private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");

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

    /**
     * Checks that the value is an absolute {@code http} or {@code https} URL with a host and no path
     * (other than empty or {@code /}), query or fragment. The failure names the property, never the
     * value.
     */
    /* default */ static void absoluteHttpUrl(final String name, final String value) {
        if (!isBareHttpUrl(value)) {
            throw new IllegalArgumentException(name
                    + " must be an absolute http or https URL with a host and no path, query or fragment");
        }
    }

    /** Checks that the value is a canonical UUID. The failure names the property, never the value. */
    /* default */ static void uuid(final String name, final String value) {
        if (CanonicalUuid.parse(value).isEmpty()) {
            throw new IllegalArgumentException(name + " must be a canonical UUID");
        }
    }

    private static boolean isBareHttpUrl(final String value) {
        boolean bare;
        try {
            final URI uri = new URI(value);
            final String path = uri.getRawPath();
            bare = uri.getScheme() != null && HTTP_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    && uri.getHost() != null
                    && (path == null || path.isEmpty() || "/".equals(path))
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException e) {
            // Not a URI at all: the same refusal as any other bad shape, raised by the caller.
            bare = false;
        }
        return bare;
    }
}
