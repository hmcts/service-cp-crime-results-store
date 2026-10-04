package uk.gov.hmcts.cp.resultsstore.domain;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes the message envelope's metadata, the top-level {@code _metadata} member, from a JSON text
 * (E8; FR-033, FR-039, FR-041). The text is parsed with Jackson 3 (which, unlike jsonb, holds a
 * {@code \u0000} escape), decimals are read exactly, member order is kept, and the tree is written back
 * as compact JSON text.
 */
public final class EnvelopeMetadata {

    /** The envelope's member. */
    public static final String MEMBER = "_metadata";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Exact decimals with their written scale; trailing content refused, as at intake. */
    private static final ObjectReader READER = MAPPER.reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);

    private static final ObjectWriter WRITER = MAPPER.writer();

    private EnvelopeMetadata() {
        // Static functions only.
    }

    /**
     * Writes the text back without its top-level {@code _metadata} member.
     *
     * @param json a JSON text
     * @return the compact text without the member
     * @throws UnreadablePayloadException when the text is not one JSON value; the message never holds the text
     */
    public static String strip(final String json) {
        final JsonNode tree;
        try {
            tree = READER.readTree(json);
        } catch (final JacksonException unreadable) {
            // Rethrown bounded: the parser's message can quote the text, so only its class travels.
            throw new UnreadablePayloadException(unreadable.getClass().getSimpleName());
        }
        if (tree == null || tree.isMissingNode()) {
            throw new UnreadablePayloadException("no JSON value");
        }
        if (tree instanceof ObjectNode object) {
            object.remove(MEMBER);
        }
        return WRITER.writeValueAsString(tree);
    }

    /** A stored text that does not parse as JSON. Its message names no part of the text. */
    public static final class UnreadablePayloadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param failure the simple name of the parser's exception class, or what was missing
         */
        public UnreadablePayloadException(final String failure) {
            super("the stored payload text is not JSON (" + failure + ")");
        }
    }
}
