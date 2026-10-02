package uk.gov.hmcts.cp.resultsstore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.resultsstore.domain.DefendantRef;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.KeyDetails;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.Projection.Extracted;
import uk.gov.hmcts.cp.resultsstore.domain.Projection.Failed;
import uk.gov.hmcts.cp.resultsstore.domain.ProjectionStatus;

class KeyDetailsExtractorTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String ABSENT = "ABSENT";

    private static final String COURT_CENTRE = "9d2e4f6a-1b3c-4d5e-8f70-a1b2c3d4e5f6";

    private static final String ROOM = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";

    private static final String YOUTH_COURT = "7b7b7b7b-0000-4000-8000-000000000007";

    private static final String CASE_1 = "c1c1c1c1-0000-4000-8000-000000000001";

    private static final String CASE_2 = "c2c2c2c2-0000-4000-8000-000000000002";

    private static final String DEFENDANT_1 = "d1d1d1d1-0000-4000-8000-000000000001";

    private static final String DEFENDANT_2 = "d2d2d2d2-0000-4000-8000-000000000002";

    private static final String MASTER_1 = "e1e1e1e1-0000-4000-8000-000000000001";

    private static final String MASTER_2 = "e2e2e2e2-0000-4000-8000-000000000002";

    /** A marker planted in bad values: a failure reason must never carry it. */
    private static final String MARKER = "PAYLOAD-TEXT-MARKER";

    private static final String FULL = """
            {"_metadata": {"id": "0b1f5a8e-1c2d-4e3f-8a9b-0c1d2e3f4a5b",
                           "name": "public.events.hearing.hearing-resulted"},
             "hearing": {"id": "6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11",
                         "jurisdictionType": "MAGISTRATES",
                         "courtCentre": {"id": "%s", "roomId": "%s", "lja": {"ljaCode": "2577"}},
                         "isSJPHearing": false,
                         "isGroupProceedings": true,
                         "youthCourt": {"youthCourtId": "%s"},
                         "youthCourtDefendantIds": ["%s"],
                         "prosecutionCases": [
                           {"id": "%s",
                            "defendants": [{"id": "%s", "masterDefendantId": "%s", "isYouth": false}]}]},
             "hearingDay": "2026-10-02",
             "sharedTime": "2026-10-02T14:19:50.706Z",
             "isReshare": true}
            """.formatted(COURT_CENTRE, ROOM, YOUTH_COURT, DEFENDANT_1, CASE_1, DEFENDANT_1, MASTER_1);

    /** Each key-detail path and the value it lands in. */
    private static final Map<String, Function<KeyDetails, Object>> COLUMN = Map.of(
            "hearing.courtCentre.id", KeyDetails::courtCentreId,
            "hearing.courtCentre.roomId", KeyDetails::courtRoomId,
            "hearing.courtCentre.lja.ljaCode", KeyDetails::ljaCode,
            "hearing.jurisdictionType", KeyDetails::jurisdictionType,
            "hearing.isSJPHearing", KeyDetails::sjp,
            "hearing.isGroupProceedings", KeyDetails::groupProceedings,
            "hearing.youthCourt.youthCourtId", KeyDetails::youthCourtId,
            "isReshare", KeyDetails::reshare);

    private final KeyDetailsExtractor extractor = new KeyDetailsExtractor();

    @Test
    void extractor_version_should_be_one() {
        assertThat(KeyDetailsExtractor.EXTRACTOR_VERSION).isEqualTo(1);
    }

    @Nested
    @DisplayName("a readable payload")
    class Readable {

        @Test
        void extract_of_a_full_payload_should_read_every_key_detail() {
            final Projection projection = extractor.extract(tree(FULL));

            assertThat(projection.status()).isEqualTo(ProjectionStatus.OK);
            assertThat(projection).isEqualTo(new Extracted(
                    new KeyDetails(UUID.fromString(COURT_CENTRE), UUID.fromString(ROOM), "2577", "MAGISTRATES",
                            Boolean.FALSE, Boolean.TRUE, UUID.fromString(YOUTH_COURT), Boolean.TRUE),
                    List.of(new DefendantRef(UUID.fromString(CASE_1), UUID.fromString(DEFENDANT_1),
                            UUID.fromString(MASTER_1))),
                    Boolean.FALSE));
        }

        @ParameterizedTest(name = "{0} = {1}")
        @MethodSource("uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractorTest#absentOrNull")
        void extract_without_an_optional_key_detail_should_leave_it_empty_and_succeed(final String path,
                final String value) {
            final Projection projection = extractor.extract(with(path, value));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(COLUMN.get(path).apply(extracted.keyDetails())).isNull());
        }

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource({
            "hearing.courtCentre,      ABSENT, hearing.courtCentre.id",
            "hearing.courtCentre,      null,   hearing.courtCentre.roomId",
            "hearing.courtCentre.lja,  ABSENT, hearing.courtCentre.lja.ljaCode",
            "hearing.youthCourt,       null,   hearing.youthCourt.youthCourtId"
        })
        void extract_without_an_optional_parent_should_leave_its_key_details_empty(final String parent,
                final String value, final String path) {
            final Projection projection = extractor.extract(with(parent, value));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(COLUMN.get(path).apply(extracted.keyDetails())).isNull());
        }

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource({
            "hearing.courtCentre,      '\"x\"', hearing.courtCentre.id",
            "hearing.courtCentre,      42,      hearing.courtCentre.roomId",
            "hearing.courtCentre.lja,  '[]',    hearing.courtCentre.lja.ljaCode",
            "hearing.youthCourt,       42,      hearing.youthCourt.youthCourtId",
            "hearing.youthCourt,       '[]',    hearing.youthCourt.youthCourtId"
        })
        void extract_with_an_optional_parent_that_is_not_an_object_should_read_it_as_absent(final String parent,
                final String value, final String path) {
            final Projection projection = extractor.extract(with(parent, value));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(COLUMN.get(path).apply(extracted.keyDetails())).isNull());
        }

        @ParameterizedTest(name = "youthCourtDefendantIds = {0}")
        @ValueSource(strings = {"\"" + MARKER + "\"", "42", "[42, null]", "{}", "null", ABSENT})
        void extract_should_not_validate_youth_court_defendant_ids(final String value) {
            final Projection projection = extractor.extract(with("hearing.youthCourtDefendantIds", value));

            assertThat(projection).isEqualTo(extractor.extract(tree(FULL)));
        }

        @Test
        void extract_should_record_the_jurisdiction_as_stated() {
            final Projection projection = extractor.extract(with("hearing.jurisdictionType", "\"NOT_A_KNOWN_ONE\""));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(extracted.keyDetails().jurisdictionType()).isEqualTo("NOT_A_KNOWN_ONE"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"hearing.courtCentre.lja.ljaCode", "hearing.jurisdictionType"})
        void extract_of_a_string_holding_an_escaped_backslash_before_u0000_should_keep_it_as_text(
                final String path) {
            final String escapedBackslash = "\\\\" + "u0000";

            final Projection projection = extractor.extract(with(path, "\"a" + escapedBackslash + "\""));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(COLUMN.get(path).apply(extracted.keyDetails()))
                            .isEqualTo("a\\" + "u0000"));
        }

        @ParameterizedTest(name = "prosecutionCases = {0}")
        @ValueSource(strings = {ABSENT, "null", "[]"})
        void extract_without_prosecution_cases_should_give_no_defendants_and_unknown_youth(final String value) {
            final Projection projection = extractor.extract(with("hearing.prosecutionCases", value));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class, extracted -> {
                assertThat(extracted.defendants()).isEmpty();
                assertThat(extracted.anySubjectIsYouth()).isNull();
            });
        }

        @ParameterizedTest(name = "defendants = {0}")
        @ValueSource(strings = {ABSENT, "null", "[]"})
        void extract_of_a_case_without_defendants_should_give_no_rows_for_it(final String value) {
            final Projection projection = extractor.extract(with("hearing.prosecutionCases.0.defendants", value));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class, extracted -> {
                assertThat(extracted.defendants()).isEmpty();
                assertThat(extracted.anySubjectIsYouth()).isNull();
            });
        }

        @ParameterizedTest(name = "masterDefendantId = {0}")
        @ValueSource(strings = {ABSENT, "null"})
        void extract_without_a_master_defendant_id_should_leave_it_empty(final String value) {
            final Projection projection = extractor.extract(
                    with("hearing.prosecutionCases.0.defendants.0.masterDefendantId", value));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(extracted.defendants()).containsExactly(
                            new DefendantRef(UUID.fromString(CASE_1), UUID.fromString(DEFENDANT_1), null)));
        }
    }

    @Nested
    @DisplayName("the defendant index")
    class Defendants {

        @Test
        void extract_should_merge_a_defendant_repeated_in_one_case_and_in_a_repeated_case() {
            final Projection projection = extractor.extract(cases("""
                    [{"id": "%1$s", "defendants": [{"id": "%2$s"}, {"id": "%2$s", "masterDefendantId": "%3$s"}]},
                     {"id": "%1$s", "defendants": [{"id": "%2$s", "masterDefendantId": "%3$s"}]}]
                    """.formatted(CASE_1, DEFENDANT_1, MASTER_1)));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(extracted.defendants()).containsExactly(
                            new DefendantRef(UUID.fromString(CASE_1), UUID.fromString(DEFENDANT_1),
                                    UUID.fromString(MASTER_1))));
        }

        @Test
        void extract_of_a_repeated_defendant_should_keep_the_first_stated_master_defendant_id() {
            final Projection projection = extractor.extract(cases("""
                    [{"id": "%1$s", "defendants": [{"id": "%2$s", "masterDefendantId": "%3$s"},
                                                   {"id": "%2$s", "masterDefendantId": "%4$s"}]}]
                    """.formatted(CASE_1, DEFENDANT_1, MASTER_1, MASTER_2)));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(extracted.defendants()).containsExactly(
                            new DefendantRef(UUID.fromString(CASE_1), UUID.fromString(DEFENDANT_1),
                                    UUID.fromString(MASTER_1))));
        }

        @Test
        void extract_of_one_defendant_on_two_cases_should_give_two_rows() {
            final Projection projection = extractor.extract(cases("""
                    [{"id": "%1$s", "defendants": [{"id": "%3$s", "masterDefendantId": "%4$s"}]},
                     {"id": "%2$s", "defendants": [{"id": "%3$s", "masterDefendantId": "%4$s"}]}]
                    """.formatted(CASE_1, CASE_2, DEFENDANT_1, MASTER_1)));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(extracted.defendants()).containsExactly(
                            new DefendantRef(UUID.fromString(CASE_1), UUID.fromString(DEFENDANT_1),
                                    UUID.fromString(MASTER_1)),
                            new DefendantRef(UUID.fromString(CASE_2), UUID.fromString(DEFENDANT_1),
                                    UUID.fromString(MASTER_1))));
        }

        @Test
        void extract_of_two_defendants_on_one_case_should_give_two_rows_in_payload_order() {
            final Projection projection = extractor.extract(cases("""
                    [{"id": "%1$s", "defendants": [{"id": "%3$s"}, {"id": "%2$s"}]}]
                    """.formatted(CASE_1, DEFENDANT_1, DEFENDANT_2)));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(extracted.defendants()).extracting(DefendantRef::defendantId)
                            .containsExactly(UUID.fromString(DEFENDANT_2), UUID.fromString(DEFENDANT_1)));
        }
    }

    @Nested
    @DisplayName("any_subject_is_youth")
    class Youth {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', value = {
            "false                | FALSE",
            "false, false         | FALSE",
            "true                 | TRUE",
            "false, true          | TRUE",
            "true, ABSENT         | TRUE",
            "null, true           | TRUE",
            "false, ABSENT        | ",
            "false, null          | ",
            "ABSENT               | ",
            "null                 | "
        })
        void extract_should_record_youth_as_three_values(final String isYouths, final Boolean expected) {
            final String[] values = isYouths.split(",");
            final StringBuilder defendants = new StringBuilder(256).append('[');
            for (int i = 0; i < values.length; i++) {
                final String value = values[i].trim();
                defendants.append(i == 0 ? "" : ", ").append("{\"id\": \"d0000000-0000-4000-8000-00000000000")
                        .append(i).append('"').append(ABSENT.equals(value) ? "" : ", \"isYouth\": " + value)
                        .append('}');
            }
            defendants.append(']');

            final Projection projection = extractor.extract(cases(
                    "[{\"id\": \"" + CASE_1 + "\", \"defendants\": " + defendants + "}]"));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(extracted.anySubjectIsYouth()).isEqualTo(expected));
        }

        @Test
        void extract_should_count_youth_across_cases() {
            final Projection projection = extractor.extract(cases("""
                    [{"id": "%1$s", "defendants": [{"id": "%3$s", "isYouth": false}]},
                     {"id": "%2$s", "defendants": [{"id": "%4$s", "isYouth": true}]}]
                    """.formatted(CASE_1, CASE_2, DEFENDANT_1, DEFENDANT_2)));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(extracted.anySubjectIsYouth()).isTrue());
        }
    }

    @Nested
    @DisplayName("an extraction failure")
    class Failure {

        @ParameterizedTest(name = "{0} = {1}")
        @MethodSource("uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractorTest#wrongTypes")
        void extract_with_a_value_of_the_wrong_type_should_fail_naming_the_path(final String path,
                final String value, final String reasonPath) {
            assertFailed(extractor.extract(with(path, value)), ExtractionFailureKind.WRONG_TYPE, reasonPath);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "hearing.courtCentre.id",
            "hearing.courtCentre.roomId",
            "hearing.youthCourt.youthCourtId",
            "hearing.prosecutionCases.0.id",
            "hearing.prosecutionCases.0.defendants.0.id",
            "hearing.prosecutionCases.0.defendants.0.masterDefendantId"
        })
        void extract_with_an_id_that_is_not_a_canonical_uuid_should_fail_naming_the_path(final String path) {
            assertFailed(extractor.extract(with(path, "\"" + MARKER + "\"")), ExtractionFailureKind.INVALID_UUID,
                    withoutPositions(path));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"hearing.courtCentre.lja.ljaCode", "hearing.jurisdictionType"})
        void extract_with_a_string_key_detail_holding_an_escaped_nul_should_fail_naming_the_path(
                final String path) {
            final String sixCharacterEscape = "\\" + "u0000";

            assertFailed(extractor.extract(with(path, "\"" + MARKER + sixCharacterEscape + "\"")),
                    ExtractionFailureKind.UNSTORABLE_TEXT, path);
        }

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource({
            "hearing.courtCentre.lja.ljaCode, \\uD800",
            "hearing.courtCentre.lja.ljaCode, \\uDC00",
            "hearing.courtCentre.lja.ljaCode, a\\uDBFFb",
            "hearing.jurisdictionType,        \\uDFFF\\uD800"
        })
        void extract_with_a_string_key_detail_holding_an_unpaired_surrogate_should_fail_naming_the_path(
                final String path, final String escapes) {
            final Projection projection = extractor.extract(with(path, "\"" + escapes + "\""));

            assertFailed(projection, ExtractionFailureKind.UNSTORABLE_TEXT, path);
        }

        @Test
        void extract_with_a_string_key_detail_holding_a_surrogate_pair_should_keep_it() {
            final Projection projection = extractor.extract(with("hearing.courtCentre.lja.ljaCode",
                    "\"a\\uD83D\\uDE00b\""));

            assertThat(projection).isInstanceOfSatisfying(Extracted.class,
                    extracted -> assertThat(extracted.keyDetails().ljaCode()).isEqualTo("a\uD83D\uDE00b"));
        }

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource({
            "hearing.prosecutionCases.0.id,              ABSENT",
            "hearing.prosecutionCases.0.id,              null",
            "hearing.prosecutionCases.0.defendants.0.id, ABSENT",
            "hearing.prosecutionCases.0.defendants.0.id, null"
        })
        void extract_without_a_required_id_should_fail_as_missing(final String path, final String value) {
            assertFailed(extractor.extract(with(path, value)), ExtractionFailureKind.MISSING, withoutPositions(path));
        }

        @Test
        void extract_should_fail_as_unexpected_when_reading_throws() {
            final JsonNode exploding = mock(JsonNode.class, invocation -> {
                throw new IllegalStateException(MARKER);
            });

            final Projection projection = extractor.extract(exploding);

            assertThat(projection).isEqualTo(new Failed("UNEXPECTED:IllegalStateException",
                    ExtractionFailureKind.UNEXPECTED));
            assertThat(projection.status()).isEqualTo(ProjectionStatus.FAILED);
        }

        @Test
        void extract_should_fail_as_unexpected_with_a_bounded_name_for_an_anonymous_exception() {
            final JsonNode exploding = mock(JsonNode.class, invocation -> {
                throw new IllegalStateException(MARKER) {
                    private static final long serialVersionUID = 1L;
                };
            });

            final Projection projection = extractor.extract(exploding);

            assertThat(projection).isInstanceOfSatisfying(Failed.class, failed -> {
                assertThat(failed.kind()).isEqualTo(ExtractionFailureKind.UNEXPECTED);
                assertThat(failed.reason()).startsWith("UNEXPECTED:KeyDetailsExtractorTest")
                        .hasSizeLessThanOrEqualTo(120)
                        .doesNotContain(MARKER);
            });
        }

        @Test
        void extract_should_cut_an_unexpected_reason_to_its_limit() {
            final JsonNode exploding = mock(JsonNode.class, invocation -> {
                throw new AnExceptionWhoseSimpleNameIsLongEnoughThatTheUnexpectedReasonBuiltFromItMustBeCutToTheOneHundredAndTwentyCharacterLimit(MARKER);
            });

            final Projection projection = extractor.extract(exploding);

            assertThat(projection).isInstanceOfSatisfying(Failed.class, failed -> {
                assertThat(failed.kind()).isEqualTo(ExtractionFailureKind.UNEXPECTED);
                assertThat(failed.reason()).hasSize(120)
                        .isEqualTo(("UNEXPECTED:" + AnExceptionWhoseSimpleNameIsLongEnoughThatTheUnexpectedReasonBuiltFromItMustBeCutToTheOneHundredAndTwentyCharacterLimit.class.getSimpleName()).substring(0, 120));
            });
        }

        @Test
        void extract_should_let_an_error_escape() {
            final StackOverflowError error = new StackOverflowError(MARKER);
            final JsonNode exploding = mock(JsonNode.class, invocation -> {
                throw error;
            });

            assertThatThrownBy(() -> extractor.extract(exploding)).isSameAs(error);
        }

        @Test
        void extract_of_a_failure_should_leave_no_key_details_or_defendants() {
            final Projection projection = extractor.extract(with("hearing.isSJPHearing", "\"yes\""));

            assertThat(projection).isEqualTo(new Failed("WRONG_TYPE:hearing.isSJPHearing",
                    ExtractionFailureKind.WRONG_TYPE));
        }

        private void assertFailed(final Projection projection, final ExtractionFailureKind kind,
                final String path) {
            assertThat(projection).isInstanceOfSatisfying(Failed.class, failed -> {
                assertThat(failed.kind()).isEqualTo(kind);
                assertThat(failed.reason()).isEqualTo(kind.name() + ":" + path)
                        .hasSizeLessThanOrEqualTo(120)
                        .doesNotContain(MARKER);
            });
            assertThat(projection.status()).isEqualTo(ProjectionStatus.FAILED);
        }
    }

    @ParameterizedTest
    @EnumSource(ExtractionFailureKind.class)
    void failure_kind_should_have_its_lower_case_metric_tag(final ExtractionFailureKind kind) {
        assertThat(kind.tag()).isEqualTo(kind.name().toLowerCase(Locale.ROOT)).matches("^[a-z_]+$");
    }

    static Stream<Arguments> absentOrNull() {
        return COLUMN.keySet().stream().sorted()
                .flatMap(path -> Stream.of(Arguments.of(path, ABSENT), Arguments.of(path, "null")));
    }

    static Stream<Arguments> wrongTypes() {
        return Stream.of(
                Arguments.of("hearing.courtCentre.id", "42", "hearing.courtCentre.id"),
                Arguments.of("hearing.courtCentre.id", "{}", "hearing.courtCentre.id"),
                Arguments.of("hearing.courtCentre.roomId", "true", "hearing.courtCentre.roomId"),
                Arguments.of("hearing.courtCentre.lja.ljaCode", "2577", "hearing.courtCentre.lja.ljaCode"),
                Arguments.of("hearing.jurisdictionType", "[\"" + MARKER + "\"]", "hearing.jurisdictionType"),
                Arguments.of("hearing.isSJPHearing", "\"" + MARKER + "\"", "hearing.isSJPHearing"),
                Arguments.of("hearing.isGroupProceedings", "1", "hearing.isGroupProceedings"),
                Arguments.of("hearing.youthCourt.youthCourtId", "42", "hearing.youthCourt.youthCourtId"),
                Arguments.of("isReshare", "\"false\"", "isReshare"),
                Arguments.of("hearing.prosecutionCases", "\"" + MARKER + "\"", "hearing.prosecutionCases"),
                Arguments.of("hearing.prosecutionCases", "{}", "hearing.prosecutionCases"),
                Arguments.of("hearing.prosecutionCases.0", "\"" + MARKER + "\"", "hearing.prosecutionCases"),
                Arguments.of("hearing.prosecutionCases.0.id", "42", "hearing.prosecutionCases.id"),
                Arguments.of("hearing.prosecutionCases.0.defendants", "{}", "hearing.prosecutionCases.defendants"),
                Arguments.of("hearing.prosecutionCases.0.defendants.0", "42", "hearing.prosecutionCases.defendants"),
                Arguments.of("hearing.prosecutionCases.0.defendants.0.id", "[]",
                        "hearing.prosecutionCases.defendants.id"),
                Arguments.of("hearing.prosecutionCases.0.defendants.0.masterDefendantId", "42",
                        "hearing.prosecutionCases.defendants.masterDefendantId"),
                Arguments.of("hearing.prosecutionCases.0.defendants.0.isYouth", "\"" + MARKER + "\"",
                        "hearing.prosecutionCases.defendants.isYouth"));
    }

    private static String withoutPositions(final String path) {
        return path.replaceAll("\\.\\d+", "");
    }

    private static JsonNode tree(final String json) {
        return MAPPER.readTree(json);
    }

    private static JsonNode cases(final String prosecutionCases) {
        return with("hearing.prosecutionCases", prosecutionCases);
    }

    /**
     * The full payload with one value replaced: raw JSON, or {@code ABSENT} to remove the key.
     * Numeric segments index arrays.
     */
    private static JsonNode with(final String path, final String rawJson) {
        final JsonNode root = tree(FULL);
        final String[] segments = path.split("\\.");
        JsonNode parent = root;
        for (int i = 0; i < segments.length - 1; i++) {
            parent = isIndex(segments[i]) ? parent.get(Integer.parseInt(segments[i])) : parent.get(segments[i]);
        }
        final String last = segments[segments.length - 1];
        if (isIndex(last)) {
            final ArrayNode array = (ArrayNode) parent;
            if (ABSENT.equals(rawJson)) {
                array.remove(Integer.parseInt(last));
            } else {
                array.set(Integer.parseInt(last), tree(rawJson));
            }
        } else {
            final ObjectNode object = (ObjectNode) parent;
            if (ABSENT.equals(rawJson)) {
                object.remove(last);
            } else {
                object.set(last, tree(rawJson));
            }
        }
        return root;
    }

    private static boolean isIndex(final String segment) {
        return segment.chars().allMatch(Character::isDigit);
    }

    /** A runtime exception whose simple name alone takes an unexpected reason past its limit. */
    private static final class AnExceptionWhoseSimpleNameIsLongEnoughThatTheUnexpectedReasonBuiltFromItMustBeCutToTheOneHundredAndTwentyCharacterLimit extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private AnExceptionWhoseSimpleNameIsLongEnoughThatTheUnexpectedReasonBuiltFromItMustBeCutToTheOneHundredAndTwentyCharacterLimit(final String message) {
            super(message);
        }
    }
}
