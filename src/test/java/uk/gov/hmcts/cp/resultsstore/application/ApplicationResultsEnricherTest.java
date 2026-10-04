package uk.gov.hmcts.cp.resultsstore.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.ApplicationResultsEnricher.Scan;
import uk.gov.hmcts.cp.resultsstore.domain.ApplicationLookupOutcome;

class ApplicationResultsEnricherTest {

    private static final String APP_A = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    private static final String APP_B = "1b2c3d4e-5f6a-4b7c-9d8e-0f1a2b3c4d5e";

    private static final String RESULT = "{'judicialResultId': 'r1', 'label': 'Granted'}";

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private final ShareIdentityParser parser = new ShareIdentityParser(mapper);

    private final ApplicationResultsEnricher enricher = new ApplicationResultsEnricher(mapper);

    @Nested
    @DisplayName("the scan")
    class TheScan {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "{'sharedTime': 'x'}",
            "{'hearing': {'id': 'h'}}",
            "{'hearing': {'courtApplications': {'id': '" + APP_A + "'}}}",
            "{'hearing': {'courtApplications': 'none'}}",
            "{'hearing': 'not an object'}"
        })
        void body_without_an_array_of_applications_should_need_no_lookup(final String body) {
            assertThat(enricher.scan(tree(body))).isEqualTo(new Scan(List.of(), 0));
        }

        @ParameterizedTest(name = "judicialResults {0}")
        @ValueSource(strings = {"", ", 'judicialResults': null", ", 'judicialResults': []"})
        void application_with_results_missing_null_or_empty_should_be_looked_up(final String results) {
            final Scan scan = enricher.scan(tree(hearing("{'id': '" + APP_A + "'" + results + "}")));

            assertThat(scan).isEqualTo(new Scan(List.of(UUID.fromString(APP_A)), 0));
        }

        @ParameterizedTest(name = "judicialResults {0}")
        @ValueSource(strings = {"[" + RESULT + "]", "{}", "'text'", "3", "true", "[1]"})
        void application_with_results_present_or_of_another_type_should_not_be_looked_up(final String results) {
            final Scan scan = enricher.scan(tree(hearing("{'id': '" + APP_A + "', 'judicialResults': " + results + "}")));

            assertThat(scan).isEqualTo(new Scan(List.of(), 0));
        }

        @Test
        void element_that_is_not_an_object_should_be_passed_over() {
            final Scan scan = enricher.scan(tree(hearing("'" + APP_A + "', null, 3, [], {'id': '" + APP_B + "'}")));

            assertThat(scan).isEqualTo(new Scan(List.of(UUID.fromString(APP_B)), 0));
        }

        @Test
        void ids_should_be_returned_in_array_order_and_each_once_whatever_its_case() {
            final Scan scan = enricher.scan(tree(hearing("{'id': '" + APP_B + "'}, {'id': '" + APP_A + "'}, "
                    + "{'id': '" + APP_B.toUpperCase(Locale.ROOT) + "', 'judicialResults': []}")));

            assertThat(scan.lookups()).containsExactly(UUID.fromString(APP_B), UUID.fromString(APP_A));
        }

        @Test
        void application_with_a_missing_or_invalid_id_should_be_skipped_and_counted() {
            final Scan scan = enricher.scan(tree(hearing("{'name': 'no id'}, {'id': null}, {'id': 7}, "
                    + "{'id': '1-1-1-1-1'}, {'id': 'not-a-uuid'}, {'id': '" + APP_A + "'}, "
                    + "{'id': 'bad', 'judicialResults': [" + RESULT + "]}")));

            assertThat(scan).isEqualTo(new Scan(List.of(UUID.fromString(APP_A)), 5));
        }

        @Test
        void nested_results_should_not_be_looked_at() {
            final Scan scan = enricher.scan(tree(hearing("{'id': '" + APP_A + "', 'judicialResults': [" + RESULT
                    + "], 'courtApplicationCases': [{'offences': [{'judicialResults': []}]}]}")));

            assertThat(scan.lookups()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the outcome of an answer")
    class TheOutcome {

        @Test
        void finalised_with_results_should_be_enriched() {
            assertThat(enricher.outcome(found("{'applicationStatus': 'FINALISED', 'judicialResults': [" + RESULT + "]}")))
                    .isEqualTo(ApplicationLookupOutcome.ENRICHED);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "{'applicationStatus': 'LISTED', 'judicialResults': [" + RESULT + "]}",
            "{'applicationStatus': 'finalised', 'judicialResults': [" + RESULT + "]}",
            "{'applicationStatus': null, 'judicialResults': [" + RESULT + "]}",
            "{'applicationStatus': 1, 'judicialResults': [" + RESULT + "]}",
            "{'applicationStatus': ['FINALISED'], 'judicialResults': [" + RESULT + "]}",
            "{'judicialResults': [" + RESULT + "]}",
            "{'applicationStatus': 'LISTED'}"
        })
        void status_missing_not_a_string_or_not_finalised_should_be_not_finalised(final String application) {
            assertThat(enricher.outcome(found(application))).isEqualTo(ApplicationLookupOutcome.NOT_FINALISED);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "{'applicationStatus': 'FINALISED'}",
            "{'applicationStatus': 'FINALISED', 'judicialResults': null}",
            "{'applicationStatus': 'FINALISED', 'judicialResults': []}"
        })
        void finalised_without_results_should_be_no_results(final String application) {
            assertThat(enricher.outcome(found(application))).isEqualTo(ApplicationLookupOutcome.NO_RESULTS);
        }

        @Test
        void not_found_should_be_not_found() {
            assertThat(enricher.outcome(new ApplicationAnswer.NotFound())).isEqualTo(ApplicationLookupOutcome.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("the enriched copy")
    class TheEnrichedCopy {

        @Test
        void finalised_results_should_be_copied_without_the_three_amendment_fields_and_in_order() {
            final String arrived = hearing("{'id': '" + APP_A + "'}");
            final JsonNode body = tree(arrived);

            final Enrichment enrichment = enricher.enrich(json(arrived), body, Map.of(UUID.fromString(APP_A),
                    found("{'applicationStatus': 'FINALISED', 'judicialResults': ["
                            + "{'b': 1, 'amendmentDate': '2026-01-01', 'isNewAmendment': true, 'amendmentReason': 'r',"
                            + " 'fourEyesApproval': {'amendmentDate': 'nested kept'}, 'amendmentReasonId': 'x',"
                            + " 'approvedDate': '2026-01-02', 'a': 2},"
                            + "{'label': 'second'}]}")));

            assertThat(enrichment.applied()).isTrue();
            final JsonNode results = application(enrichment.tree(), 0).get("judicialResults");
            assertThat(results).hasSize(2);
            assertThat(results.get(0).propertyNames())
                    .containsExactly("b", "isNewAmendment", "fourEyesApproval", "approvedDate", "a");
            assertThat(results.get(0).path("fourEyesApproval").path("amendmentDate").stringValue())
                    .isEqualTo("nested kept");
            assertThat(results.get(1).path("label").stringValue()).isEqualTo("second");
        }

        @Test
        void only_the_results_should_be_copied_from_the_answer() {
            final String arrived = hearing("{'id': '" + APP_A + "', 'applicationStatus': 'LISTED'}");

            final Enrichment enrichment = enricher.enrich(json(arrived), tree(arrived), Map.of(UUID.fromString(APP_A),
                    found("{'applicationStatus': 'FINALISED', 'extra': 'not copied', 'judicialResults': [" + RESULT
                            + "], 'courtApplicationCases': [{'offences': [{'judicialResults': [" + RESULT + "]}]}]}")));

            final JsonNode application = application(enrichment.tree(), 0);
            assertThat(application.propertyNames()).containsExactly("id", "applicationStatus", "judicialResults");
            assertThat(application.path("applicationStatus").stringValue()).isEqualTo("LISTED");
        }

        @Test
        void nested_results_in_the_share_should_be_neither_copied_nor_changed() {
            final String nested = "'courtApplicationCases': [{'offences': [{'judicialResults': []}]}]";
            final String arrived = hearing("{'id': '" + APP_A + "', " + nested + "}");
            final JsonNode body = tree(arrived);

            final Enrichment enrichment = enricher.enrich(json(arrived), body,
                    Map.of(UUID.fromString(APP_A), finalised()));

            assertThat(application(enrichment.tree(), 0).path("courtApplicationCases"))
                    .isEqualTo(application(body, 0).path("courtApplicationCases"));
        }

        @ParameterizedTest(name = "judicialResults {0}")
        @ValueSource(strings = {"null", "[]"})
        void replaced_results_should_keep_their_place(final String results) {
            final String arrived = hearing("{'id': '" + APP_A + "', 'judicialResults': " + results + ", 'z': 1}");

            final Enrichment enrichment = enricher.enrich(json(arrived), tree(arrived),
                    Map.of(UUID.fromString(APP_A), finalised()));

            assertThat(application(enrichment.tree(), 0).propertyNames()).containsExactly("id", "judicialResults", "z");
            assertThat(application(enrichment.tree(), 0).get("judicialResults")).hasSize(1);
        }

        @Test
        void added_results_should_go_last() {
            final String arrived = hearing("{'id': '" + APP_A + "', 'z': 1}");

            final Enrichment enrichment = enricher.enrich(json(arrived), tree(arrived),
                    Map.of(UUID.fromString(APP_A), finalised()));

            assertThat(application(enrichment.tree(), 0).propertyNames()).containsExactly("id", "z", "judicialResults");
        }

        @Test
        void element_of_the_results_that_is_not_an_object_should_be_copied_unchanged() {
            final String arrived = hearing("{'id': '" + APP_A + "'}");

            final Enrichment enrichment = enricher.enrich(json(arrived), tree(arrived), Map.of(UUID.fromString(APP_A),
                    found("{'applicationStatus': 'FINALISED', 'judicialResults': ['text', 3, null, ['amendmentDate']]}")));

            assertThat(application(enrichment.tree(), 0).get("judicialResults"))
                    .isEqualTo(tree("['text', 3, null, ['amendmentDate']]"));
        }

        @Test
        void one_answer_should_apply_to_every_application_with_that_id_whatever_its_case() {
            final String arrived = hearing("{'id': '" + APP_A + "'}, {'id': '" + APP_A.toUpperCase(Locale.ROOT)
                    + "', 'judicialResults': []}");

            final Enrichment enrichment = enricher.enrich(json(arrived), tree(arrived),
                    Map.of(UUID.fromString(APP_A), finalised()));

            assertThat(application(enrichment.tree(), 0).get("judicialResults")).hasSize(1);
            assertThat(application(enrichment.tree(), 1).get("judicialResults")).hasSize(1);
        }

        @Test
        void application_already_resulted_or_with_an_invalid_id_should_be_left_as_it_arrived() {
            final String arrived = hearing("{'id': '" + APP_A + "', 'judicialResults': [{'label': 'own'}]}, "
                    + "{'id': 'not-a-uuid'}, {'id': '" + APP_B + "'}");
            final JsonNode body = tree(arrived);
            final Map<UUID, ApplicationAnswer> answers = new LinkedHashMap<>();
            answers.put(UUID.fromString(APP_A), finalised());
            answers.put(UUID.fromString(APP_B), finalised());

            final Enrichment enrichment = enricher.enrich(json(arrived), body, answers);

            assertThat(application(enrichment.tree(), 0)).isEqualTo(application(body, 0));
            assertThat(application(enrichment.tree(), 1)).isEqualTo(application(body, 1));
            assertThat(application(enrichment.tree(), 2).get("judicialResults")).hasSize(1);
            assertThat(enrichment.applied()).isTrue();
        }

        @Test
        void two_applications_with_one_enriched_should_be_applied() {
            final String arrived = hearing("{'id': '" + APP_A + "'}, {'id': '" + APP_B + "'}");
            final Map<UUID, ApplicationAnswer> answers = new LinkedHashMap<>();
            answers.put(UUID.fromString(APP_A), new ApplicationAnswer.NotFound());
            answers.put(UUID.fromString(APP_B), finalised());

            final Enrichment enrichment = enricher.enrich(json(arrived), tree(arrived), answers);

            assertThat(enrichment.applied()).isTrue();
            assertThat(application(enrichment.tree(), 0).has("judicialResults")).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "{'applicationStatus': 'LISTED', 'judicialResults': [" + RESULT + "]}",
            "{'applicationStatus': 'FINALISED', 'judicialResults': []}",
            "NOT_FOUND"
        })
        void nothing_added_should_give_the_arrived_text_and_body_unapplied(final String answer) {
            final String arrived = "{\"hearing\":  {\"courtApplications\": [{\"id\": \"" + APP_A + "\"}]}}";
            final JsonNode body = parser.readTree(arrived);
            final ApplicationAnswer given = "NOT_FOUND".equals(answer) ? new ApplicationAnswer.NotFound() : found(answer);

            final Enrichment enrichment = enricher.enrich(arrived, body, Map.of(UUID.fromString(APP_A), given));

            assertThat(enrichment).isEqualTo(new Enrichment(body, arrived, false));
        }

        @Test
        void the_arrived_body_should_not_be_changed() {
            final String arrived = hearing("{'id': '" + APP_A + "', 'judicialResults': []}");
            final JsonNode body = tree(arrived);
            final JsonNode before = body.deepCopy();

            final Enrichment enrichment = enricher.enrich(json(arrived), body,
                    Map.of(UUID.fromString(APP_A), finalised()));

            assertThat(enrichment.applied()).isTrue();
            assertThat(body).isEqualTo(before);
            assertThat(enrichment.tree()).isNotSameAs(body);
        }

        @Test
        void enriched_copy_should_be_written_compact_with_non_ascii_escaped() {
            final String arrived = "{\"hearing\": {\"note\": \"caf\u00e9\", \"courtApplications\": [{\"id\": \""
                    + APP_A + "\"}]}}";
            final JsonNode body = parser.readTree(arrived);

            final Enrichment enrichment = enricher.enrich(arrived, body, Map.of(UUID.fromString(APP_A),
                    found("{\"applicationStatus\": \"FINALISED\", \"judicialResults\": [{\"label\": \"\\udc00 \u00e9\"}]}")));

            assertThat(enrichment.parsedCopy()).isEqualTo("{\"hearing\":{\"note\":\"caf\\u00E9\",\"courtApplications\":"
                    + "[{\"id\":\"" + APP_A + "\",\"judicialResults\":[{\"label\":\"\\uDC00 \\u00E9\"}]}]}}");
            assertThat(parser.readTree(enrichment.parsedCopy())).isEqualTo(enrichment.tree());
        }

        @Test
        void enriched_copy_should_ignore_an_indenting_mapper() {
            final ObjectMapper indenting = JsonMapper.builder()
                    .enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT).build();
            final String arrived = hearing("{'id': '" + APP_A + "'}");

            final Enrichment enrichment = new ApplicationResultsEnricher(indenting).enrich(json(arrived), tree(arrived),
                    Map.of(UUID.fromString(APP_A), finalised()));

            assertThat(enrichment.parsedCopy()).doesNotContain("\n").doesNotContain(": ");
        }
    }

    private ApplicationAnswer finalised() {
        return found("{'applicationStatus': 'FINALISED', 'judicialResults': [" + RESULT + "]}");
    }

    private ApplicationAnswer found(final String application) {
        return new ApplicationAnswer.Found(tree(application));
    }

    private JsonNode tree(final String singleQuoted) {
        return parser.readTree(json(singleQuoted));
    }

    private static JsonNode application(final JsonNode body, final int index) {
        return body.path("hearing").path("courtApplications").get(index);
    }

    private static String hearing(final String applications) {
        return "{'hearing': {'id': 'h', 'courtApplications': [" + applications + "]}, 'hearingDay': '2026-10-03'}";
    }

    private static String json(final String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }
}
