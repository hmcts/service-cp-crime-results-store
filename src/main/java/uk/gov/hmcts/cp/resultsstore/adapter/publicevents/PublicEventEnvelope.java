package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The identity fields of a {@code public.events.hearing.hearing-resulted} envelope.
 *
 * <p>Only the fields that identify a share are read: the hearing, the hearing day and the time it
 * was shared. Each is {@code null} where the payload does not carry it as a string.
 *
 * @param hearingId  {@code hearing.id}
 * @param hearingDay {@code hearingDay}
 * @param sharedTime {@code sharedTime}
 */
public record PublicEventEnvelope(String hearingId, String hearingDay, String sharedTime) {

    /**
     * Reads the identity fields from a message body.
     *
     * @param mapper the mapper to parse with
     * @param body   the JMS text message body
     * @return the identity fields
     * @throws tools.jackson.core.JacksonException if the body is not JSON
     * @throws IllegalArgumentException            if the body is JSON but not an object
     */
    public static PublicEventEnvelope parse(final ObjectMapper mapper, final String body) {
        final JsonNode envelope = mapper.readTree(body);
        if (!envelope.isObject()) {
            throw new IllegalArgumentException("a public event body is a JSON object, and this one is not");
        }
        return new PublicEventEnvelope(
                text(envelope.path("hearing").path("id")),
                text(envelope.path("hearingDay")),
                text(envelope.path("sharedTime")));
    }

    private static String text(final JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }
}
