package uk.gov.hmcts.cp.resultsstore.application;

import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.resultsstore.domain.CanonicalUuid;
import uk.gov.hmcts.cp.resultsstore.domain.DefendantRef;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.KeyDetails;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;

/**
 * Reads the key details and the defendant index from a share's payload (FR-018, FR-019, FR-026,
 * FR-029 to FR-031). Pure and in memory. It catches {@link RuntimeException} and returns
 * {@link Projection.Failed}; an {@link Error} escapes.
 *
 * <p>A field of the wrong type, an id that is not a canonical UUID, or a missing required id fails
 * the whole extraction with a reason naming the field's path (array positions left out), so the
 * reason is a short bounded code and never holds payload text. Anything else thrown while reading
 * is recorded as {@code UNEXPECTED:<class>}. Only {@link RuntimeException} is caught.
 */
public class KeyDetailsExtractor {

    /** Raised when the reading rules change, so the sweep retries rows read by an older version. */
    public static final int EXTRACTOR_VERSION = 1;

    private static final int REASON_LIMIT = 120;

    private static final String SEPARATOR = ":";

    private static final char NUL = '\u0000';

    private static final String HEARING = "hearing";

    private static final String COURT_CENTRE = "courtCentre";

    private static final String ID = "id";

    private static final String CASES = "prosecutionCases";

    private static final String DEFENDANTS = "defendants";

    private static final String P_COURT_CENTRE_ID = "hearing.courtCentre.id";

    private static final String P_ROOM_ID = "hearing.courtCentre.roomId";

    private static final String P_LJA_CODE = "hearing.courtCentre.lja.ljaCode";

    private static final String P_JURISDICTION = "hearing.jurisdictionType";

    private static final String P_SJP = "hearing.isSJPHearing";

    private static final String P_GROUP = "hearing.isGroupProceedings";

    private static final String P_YOUTH_COURT_ID = "hearing.youthCourt.youthCourtId";

    private static final String P_RESHARE = "isReshare";

    private static final String P_CASES = "hearing.prosecutionCases";

    private static final String P_CASE_ID = "hearing.prosecutionCases.id";

    private static final String P_DEFENDANTS = "hearing.prosecutionCases.defendants";

    private static final String P_DEFENDANT_ID = "hearing.prosecutionCases.defendants.id";

    private static final String P_MASTER_ID = "hearing.prosecutionCases.defendants.masterDefendantId";

    private static final String P_IS_YOUTH = "hearing.prosecutionCases.defendants.isYouth";

    /**
     * Reads the key details.
     *
     * @param body the parsed payload
     * @return the key details, or why they could not be read
     */
    // A RuntimeException must not fail the share (FR-030, Principle V): it becomes a Failed
    // UNEXPECTED projection. Error and other Throwables are not caught and escape to the caller.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public Projection extract(final JsonNode body) {
        Projection projection;
        try {
            projection = read(body);
        } catch (final FieldProblem problem) {
            projection = new Projection.Failed(problem.reason(), problem.kind());
        } catch (final RuntimeException unexpected) {
            // Returned as Failed. If the store transaction then commits, the share is stored FAILED
            // and the sweep may re-extract it (FR-033); if that transaction fails, nothing is
            // stored and the broker redelivers the message.
            projection = unexpected(unexpected);
        }
        return projection;
    }

    /**
     * The failure recorded for a runtime failure met while reading key details, here or in the sweep:
     * {@code UNEXPECTED:<class>}, bounded, never the exception's message.
     *
     * @param unexpected what was thrown
     * @return the {@code UNEXPECTED} failure naming its class
     */
    public static Projection.Failed unexpected(final RuntimeException unexpected) {
        return new Projection.Failed(
                bounded(ExtractionFailureKind.UNEXPECTED.name() + SEPARATOR + className(unexpected)),
                ExtractionFailureKind.UNEXPECTED);
    }

    private static Projection read(final JsonNode body) {
        final JsonNode hearing = body.path(HEARING);
        final JsonNode courtCentre = hearing.path(COURT_CENTRE);
        final KeyDetails keyDetails = new KeyDetails(
                optionalUuid(courtCentre.path(ID), P_COURT_CENTRE_ID),
                optionalUuid(courtCentre.path("roomId"), P_ROOM_ID),
                storableString(courtCentre.path("lja").path("ljaCode"), P_LJA_CODE),
                storableString(hearing.path("jurisdictionType"), P_JURISDICTION),
                optionalBoolean(hearing.path("isSJPHearing"), P_SJP),
                optionalBoolean(hearing.path("isGroupProceedings"), P_GROUP),
                optionalUuid(hearing.path("youthCourt").path("youthCourtId"), P_YOUTH_COURT_ID),
                optionalBoolean(body.path(P_RESHARE), P_RESHARE));
        final List<Subject> subjects = objects(hearing.path(CASES), P_CASES)
                .flatMap(KeyDetailsExtractor::subjectsOfCase)
                .toList();
        return new Projection.Extracted(keyDetails, merged(subjects), anySubjectIsYouth(subjects));
    }

    private static Stream<Subject> subjectsOfCase(final JsonNode prosecutionCase) {
        final UUID caseId = requiredUuid(prosecutionCase.path(ID), P_CASE_ID);
        return objects(prosecutionCase.path(DEFENDANTS), P_DEFENDANTS)
                .map(defendant -> new Subject(
                        new DefendantRef(caseId, requiredUuid(defendant.path(ID), P_DEFENDANT_ID),
                                optionalUuid(defendant.path("masterDefendantId"), P_MASTER_ID)),
                        optionalBoolean(defendant.path("isYouth"), P_IS_YOUTH)));
    }

    /** One row per (case, defendant), in first-seen order; a repeat may add the master defendant id. */
    private static List<DefendantRef> merged(final List<Subject> subjects) {
        final Map<List<UUID>, DefendantRef> rows = subjects.stream()
                .map(Subject::ref)
                .collect(Collectors.toMap(
                        ref -> List.of(ref.caseId(), ref.defendantId()),
                        ref -> ref,
                        (first, repeat) -> first.masterDefendantId() == null ? repeat : first,
                        LinkedHashMap::new));
        return List.copyOf(rows.values());
    }

    /** TRUE if any subject is a youth; FALSE only if there is one and all say not; else unknown. */
    private static Boolean anySubjectIsYouth(final List<Subject> subjects) {
        Boolean youth = null;
        if (subjects.stream().anyMatch(subject -> Boolean.TRUE.equals(subject.isYouth()))) {
            youth = Boolean.TRUE;
        } else if (!subjects.isEmpty() && subjects.stream().allMatch(subject -> subject.isYouth() != null)) {
            youth = Boolean.FALSE;
        }
        return youth;
    }

    /** The elements of an optional array of objects; absent or null is no elements. */
    private static Stream<JsonNode> objects(final JsonNode node, final String path) {
        Stream<JsonNode> elements = Stream.empty();
        if (isStated(node)) {
            if (!node.isArray() || !node.valueStream().allMatch(JsonNode::isObject)) {
                throw new FieldProblem(ExtractionFailureKind.WRONG_TYPE, path);
            }
            elements = node.valueStream();
        }
        return elements;
    }

    private static UUID requiredUuid(final JsonNode node, final String path) {
        if (!isStated(node)) {
            throw new FieldProblem(ExtractionFailureKind.MISSING, path);
        }
        return optionalUuid(node, path);
    }

    private static UUID optionalUuid(final JsonNode node, final String path) {
        final String text = optionalString(node, path);
        return text == null ? null : CanonicalUuid.parse(text)
                .orElseThrow(() -> new FieldProblem(ExtractionFailureKind.INVALID_UUID, path));
    }

    private static String optionalString(final JsonNode node, final String path) {
        String value = null;
        if (isStated(node)) {
            if (!node.isString()) {
                throw new FieldProblem(ExtractionFailureKind.WRONG_TYPE, path);
            }
            value = node.stringValue();
        }
        return value;
    }

    /**
     * An optional string bound for a {@code text} column, which cannot hold U+0000 or an unpaired
     * UTF-16 surrogate (research R8): such a value fails the extraction so the share is stored
     * {@code FAILED}, not refused.
     */
    private static String storableString(final JsonNode node, final String path) {
        final String value = optionalString(node, path);
        if (value != null && !isStorable(value)) {
            throw new FieldProblem(ExtractionFailureKind.UNSTORABLE_TEXT, path);
        }
        return value;
    }

    /** No U+0000, and every surrogate is half of a high-then-low pair. */
    private static boolean isStorable(final String value) {
        boolean storable = value.indexOf(NUL) < 0;
        int index = 0;
        while (storable && index < value.length()) {
            final char current = value.charAt(index);
            if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                index += 2;
            } else {
                storable = !Character.isSurrogate(current);
                index++;
            }
        }
        return storable;
    }

    private static Boolean optionalBoolean(final JsonNode node, final String path) {
        Boolean value = null;
        if (isStated(node)) {
            if (!node.isBoolean()) {
                throw new FieldProblem(ExtractionFailureKind.WRONG_TYPE, path);
            }
            value = node.booleanValue();
        }
        return value;
    }

    /** Present and not JSON null. */
    private static boolean isStated(final JsonNode node) {
        return !node.isMissingNode() && !node.isNull();
    }

    /** The simple class name; for an anonymous class, the binary name without its package. */
    private static String className(final RuntimeException unexpected) {
        final Class<?> type = unexpected.getClass();
        final String simple = type.getSimpleName();
        return simple.isEmpty() ? type.getName().substring(type.getName().lastIndexOf('.') + 1) : simple;
    }

    private static String bounded(final String reason) {
        return reason.length() > REASON_LIMIT ? reason.substring(0, REASON_LIMIT) : reason;
    }

    /** A defendant as read, with its stated youth flag. */
    private record Subject(DefendantRef ref, Boolean isYouth) {
    }

    /** A field that breaks the reading rules. Carries only the kind and the schema path. */
    private static final class FieldProblem extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final ExtractionFailureKind failureKind;

        private final String path;

        private FieldProblem(final ExtractionFailureKind failureKind, final String path) {
            // No message and no stack trace: the reason is built from the kind and the path alone.
            super(null, null, false, false);
            this.failureKind = failureKind;
            this.path = path;
        }

        private ExtractionFailureKind kind() {
            return failureKind;
        }

        private String reason() {
            return bounded(failureKind.name() + SEPARATOR + path);
        }
    }
}
