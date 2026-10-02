package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import uk.gov.hmcts.cp.resultsstore.domain.CanonicalUuid;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.ShareIdentity;

/**
 * Reads a message's text as a share or says why it is not one (research R7, R8; FR-007 to FR-009).
 *
 * <p>Only {@code hearing.id}, {@code hearingDay} and {@code sharedTime} are checked; nothing else in
 * the payload is validated. The first failing field, in that order, names the reason, and every
 * part that did parse is kept for the receipt.
 */
public class ShareIdentityParser {

    private static final char NUL = '\u0000';

    private final ObjectReader reader;

    /**
     * Creates the parser.
     *
     * @param mapper the application's mapper; trailing content is refused whatever its default
     */
    public ShareIdentityParser(final ObjectMapper mapper) {
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Reads the message text.
     *
     * @param text the text of a {@code TextMessage}, or {@code null} when the message was not one
     * @return the share, or why it is not one
     */
    public Reading read(final String text) {
        final Reading reading;
        if (text == null) {
            reading = NotShare.because(NonShareReason.NOT_TEXT_MESSAGE);
        } else if (text.indexOf(NUL) >= 0) {
            reading = NotShare.because(NonShareReason.NUL_CHARACTER);
        } else {
            reading = readJson(text);
        }
        return reading;
    }

    private Reading readJson(final String text) {
        Reading reading;
        try {
            final JsonNode body = reader.readTree(text);
            reading = body.isObject() ? identify(body) : notJsonOrNotObject(body);
        } catch (final JacksonException unreadable) {
            // A recorded outcome: the receipt says the body is not JSON.
            reading = NotShare.because(NonShareReason.NOT_JSON);
        }
        return reading;
    }

    private static Reading notJsonOrNotObject(final JsonNode body) {
        // Blank text reads as no value at all rather than failing.
        return NotShare.because(body.isMissingNode() ? NonShareReason.NOT_JSON : NonShareReason.NOT_OBJECT);
    }

    private static Reading identify(final JsonNode body) {
        final String rawHearingId = text(body.path("hearing").path("id"));
        final String rawHearingDay = text(body.path("hearingDay"));
        final String rawSharedTime = text(body.path("sharedTime"));
        final UUID hearingId = CanonicalUuid.parse(rawHearingId).orElse(null);
        final LocalDate hearingDay = date(rawHearingDay).orElse(null);
        final Instant sharedAt = instant(rawSharedTime).orElse(null);

        return Stream.of(
                        problem(rawHearingId, hearingId, NonShareReason.MISSING_HEARING_ID,
                                NonShareReason.INVALID_HEARING_ID),
                        problem(rawHearingDay, hearingDay, NonShareReason.MISSING_HEARING_DAY,
                                NonShareReason.INVALID_HEARING_DAY),
                        problem(rawSharedTime, sharedAt, NonShareReason.MISSING_SHARED_TIME,
                                NonShareReason.INVALID_SHARED_TIME))
                .flatMap(Optional::stream)
                .findFirst()
                .<Reading>map(reason -> new NotShare(reason, hearingId, hearingDay, sharedAt))
                .orElseGet(() -> new Share(new ShareIdentity(hearingId, hearingDay, sharedAt,
                        rawHearingId, rawHearingDay, rawSharedTime), body));
    }

    private static Optional<NonShareReason> problem(final String raw, final Object parsed,
            final NonShareReason missing, final NonShareReason invalid) {
        final Optional<NonShareReason> problem;
        if (raw == null) {
            problem = Optional.of(missing);
        } else if (parsed == null) {
            problem = Optional.of(invalid);
        } else {
            problem = Optional.empty();
        }
        return problem;
    }

    /** A string value, or {@code null} for an absent, null or non-string node (a missing field). */
    private static String text(final JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }

    private static Optional<LocalDate> date(final String raw) {
        Optional<LocalDate> date = Optional.empty();
        if (raw != null) {
            try {
                date = Optional.of(LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE));
            } catch (final DateTimeParseException invalid) {
                // A recorded outcome: the receipt says the hearing day is invalid.
                date = Optional.empty();
            }
        }
        return date;
    }

    private static Optional<Instant> instant(final String raw) {
        Optional<Instant> instant = Optional.empty();
        if (raw != null) {
            try {
                instant = Optional.of(OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
            } catch (final DateTimeParseException invalid) {
                // A recorded outcome: the receipt says the shared time is invalid.
                instant = Optional.empty();
            }
        }
        return instant;
    }

    /** What a message's text was read as. */
    public sealed interface Reading permits Share, NotShare {
    }

    /**
     * A share.
     *
     * @param identity its identity
     * @param body     the parsed body, for the key-details extraction
     */
    public record Share(ShareIdentity identity, JsonNode body) implements Reading {
    }

    /**
     * Not a share, with whichever identity parts did parse.
     *
     * @param reason     why
     * @param hearingId  {@code hearing.id} if it parsed
     * @param hearingDay {@code hearingDay} if it parsed
     * @param sharedAt   {@code sharedTime} if it parsed
     */
    public record NotShare(NonShareReason reason, UUID hearingId, LocalDate hearingDay, Instant sharedAt)
            implements Reading {

        /**
         * Not a share, with no identity part.
         *
         * @param reason why
         * @return the reading
         */
        public static NotShare because(final NonShareReason reason) {
            return new NotShare(reason, null, null, null);
        }
    }
}
