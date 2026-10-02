package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads a UUID only in its canonical 8-4-4-4-12 hex form. {@link UUID#fromString} alone accepts
 * short forms such as {@code 1-1-1-1-1}, which the event schema does not (research R7).
 */
public final class CanonicalUuid {

    private static final Pattern CANONICAL =
            Pattern.compile("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$");

    private CanonicalUuid() {
        // Static functions only.
    }

    /**
     * Reads the text as a canonical UUID.
     *
     * @param text the text, possibly {@code null}
     * @return the UUID, or empty when the text is not one in canonical form
     */
    public static Optional<UUID> parse(final String text) {
        return Optional.ofNullable(text)
                .filter(candidate -> CANONICAL.matcher(candidate).matches())
                .map(UUID::fromString);
    }
}
