package uk.gov.hmcts.cp.resultsstore.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.resultsstore.domain.ApplicationLookupOutcome;
import uk.gov.hmcts.cp.resultsstore.domain.CanonicalUuid;

/**
 * Adds progression's finalised application results to a share, as results does before it stores a
 * payload (specs/002-enrichment FR-002 to FR-015; research R4 to R8). Pure: no I/O, no state.
 *
 * <p>Only {@code hearing.courtApplications[]} is looked at. An application is a candidate when its
 * top-level {@code judicialResults} is missing, JSON {@code null} or an empty array; its {@code id}
 * must be a canonical UUID, or it is skipped and counted. From a {@code FINALISED} answer with results,
 * only {@code judicialResults} is copied, each object element without the three amendment fields. The
 * work is done on a deep copy, so the arrived body is never changed.
 */
public class ApplicationResultsEnricher {

    private static final String HEARING = "hearing";

    private static final String COURT_APPLICATIONS = "courtApplications";

    private static final String ID = "id";

    private static final String JUDICIAL_RESULTS = "judicialResults";

    private static final String APPLICATION_STATUS = "applicationStatus";

    private static final String FINALISED = "FINALISED";

    /** Removed from each copied result, at its top level only, as results removes them. */
    private static final List<String> AMENDMENT_FIELDS = List.of("amendmentDate", "amendmentReason",
            "amendmentReasonId");

    private final ObjectWriter writer;

    /**
     * Creates the enricher.
     *
     * @param mapper the application's mapper; the writer is derived from it, never changing it
     */
    public ApplicationResultsEnricher(final ObjectMapper mapper) {
        this.writer = mapper.writer()
                .without(SerializationFeature.INDENT_OUTPUT)
                .with(JsonWriteFeature.ESCAPE_NON_ASCII);
    }

    /**
     * The applications that need a lookup.
     *
     * @param body the share's parsed body
     * @return the distinct ids, by UUID, in first-seen array order; and how many candidates had a
     *     missing or invalid id
     */
    public Scan scan(final JsonNode body) {
        final Set<UUID> lookups = new LinkedHashSet<>();
        int invalidIds = 0;
        for (final JsonNode application : applications(body)) {
            if (needsResults(application)) {
                final Optional<UUID> id = idOf(application);
                if (id.isPresent()) {
                    lookups.add(id.get());
                } else {
                    invalidIds++;
                }
            }
        }
        return new Scan(List.copyOf(lookups), invalidIds);
    }

    /**
     * What one answer comes to.
     *
     * @param answer progression's answer
     * @return its outcome; never {@link ApplicationLookupOutcome#INVALID_ID}
     */
    public ApplicationLookupOutcome outcome(final ApplicationAnswer answer) {
        return switch (answer) {
            case ApplicationAnswer.NotFound _ -> ApplicationLookupOutcome.NOT_FOUND;
            case ApplicationAnswer.Found found -> outcomeOf(found.courtApplication());
        };
    }

    /**
     * The working copy.
     *
     * @param arrivedText the message text as it arrived
     * @param body        its parsed body; not changed
     * @param answers     progression's answer for each id the scan named
     * @return the enriched copy, or the arrived body and text when nothing was added
     */
    public Enrichment enrich(final String arrivedText, final JsonNode body,
            final Map<UUID, ApplicationAnswer> answers) {
        final JsonNode copy = body.deepCopy();
        boolean applied = false;
        for (final JsonNode application : applications(copy)) {
            final JsonNode results = needsResults(application)
                    ? idOf(application).map(answers::get).map(this::resultsToCopy).orElse(null)
                    : null;
            if (results != null) {
                // An existing key keeps its place; a missing one is added last (FR-012).
                ((ObjectNode) application).set(JUDICIAL_RESULTS, results);
                applied = true;
            }
        }
        return applied
                ? new Enrichment(copy, writer.writeValueAsString(copy), true)
                : new Enrichment(body, arrivedText, false);
    }

    private static Iterable<JsonNode> applications(final JsonNode body) {
        final JsonNode applications = body.path(HEARING).path(COURT_APPLICATIONS);
        return applications.isArray() ? applications.values() : List.of();
    }

    private static boolean needsResults(final JsonNode application) {
        final JsonNode results = application.get(JUDICIAL_RESULTS);
        return application.isObject()
                && (results == null || results.isNull() || results.isArray() && results.isEmpty());
    }

    private static Optional<UUID> idOf(final JsonNode application) {
        final JsonNode id = application.get(ID);
        return id != null && id.isString() ? CanonicalUuid.parse(id.stringValue()) : Optional.empty();
    }

    private static ApplicationLookupOutcome outcomeOf(final JsonNode application) {
        final JsonNode status = application.get(APPLICATION_STATUS);
        final JsonNode results = application.get(JUDICIAL_RESULTS);
        final ApplicationLookupOutcome outcome;
        if (status == null || !status.isString() || !FINALISED.equals(status.stringValue())) {
            outcome = ApplicationLookupOutcome.NOT_FINALISED;
        } else if (results != null && results.isArray() && !results.isEmpty()) {
            outcome = ApplicationLookupOutcome.ENRICHED;
        } else {
            outcome = ApplicationLookupOutcome.NO_RESULTS;
        }
        return outcome;
    }

    /** The results to set, or {@code null} when the answer has none to give. */
    private ArrayNode resultsToCopy(final ApplicationAnswer answer) {
        ArrayNode copied = null;
        if (answer instanceof ApplicationAnswer.Found found
                && outcome(answer) == ApplicationLookupOutcome.ENRICHED) {
            copied = (ArrayNode) found.courtApplication().get(JUDICIAL_RESULTS).deepCopy();
            for (final JsonNode result : copied.values()) {
                if (result.isObject()) {
                    ((ObjectNode) result).remove(AMENDMENT_FIELDS);
                }
            }
        }
        return copied;
    }

    /**
     * What the scan found.
     *
     * @param lookups    the distinct application ids to look up, in array order
     * @param invalidIds how many applications needing results had a missing or invalid id
     */
    public record Scan(List<UUID> lookups, int invalidIds) {
    }
}
