package uk.gov.hmcts.cp.resultsstore.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parity with results' own enricher, on results' own fixtures (cpp-context-results@063472490,
 * {@code application-final-results-enricher}; research R4). Content is compared {@code STRICT}: no
 * extra or missing field, arrays in order; key order is asserted on the tree separately.
 */
class ApplicationResultsParityTest {

    private static final String APP1 = "9bfd5897-3a7f-4d38-90d7-b1368c29b2ac";

    private static final String APP2 = "47663a56-97ce-4e59-b660-67a23fadd8d3";

    private static final String LISTED = "18299a7a-69e0-4509-a1da-d71700b6e9f9";

    private static final String APP1_HEARING = "app1_adjourned_hearing.json";

    private static final String APP1_FINALISED = "app1_progression_finalised.json";

    private static final String APP2_FINALISED = "app2_progression_finalised_resultsamended.json";

    private static final String PROGRESSION_LISTED = "app_progression_listed.json";

    private static final String JUDICIAL_RESULTS = "judicialResults";

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final ShareIdentityParser parser = new ShareIdentityParser(mapper);

    private final ApplicationResultsEnricher enricher = new ApplicationResultsEnricher(mapper);

    private final List<UUID> asked = new ArrayList<>();

    @Test
    void app1_finalised_should_be_enriched_as_results_enriches_it() throws JSONException {
        final Enrichment enrichment = enrich(APP1_HEARING, id -> found(APP1_FINALISED));

        assertThat(asked).containsExactly(UUID.fromString(APP1));
        assertThat(enrichment.applied()).isTrue();
        assertSameContent("app1_adjourned_hearing_enriched.json", enrichment);
        assertThat(application(enrichment.tree(), 0).propertyNames()).last().isEqualTo(JUDICIAL_RESULTS);
    }

    @Test
    void app2_results_should_lose_the_three_amendment_fields_as_results_removes_them() throws JSONException {
        final Enrichment enrichment = enrich("app2_adjourned_hearing.json", id -> found(APP2_FINALISED));

        assertThat(asked).containsExactly(UUID.fromString(APP2));
        assertThat(enrichment.applied()).isTrue();
        assertSameContent("app2_adjourned_hearing_enriched.json", enrichment);
        final JsonNode result = application(enrichment.tree(), 0).path(JUDICIAL_RESULTS).get(0);
        assertThat(result.has("amendmentDate") || result.has("amendmentReason") || result.has("amendmentReasonId"))
                .isFalse();
        assertThat(result.has("isNewAmendment")).isTrue();
    }

    @Test
    void app3_already_resulted_should_make_no_lookup_and_stay_as_it_arrived() throws JSONException {
        final Enrichment enrichment = enrich("app3_resulted_hearing.json", id -> found(APP1_FINALISED));

        assertThat(asked).isEmpty();
        assertThat(enrichment.applied()).isFalse();
        assertSameContent("app3_resulted_hearing.json", enrichment);
    }

    @Test
    void app1_with_a_listed_answer_should_stay_as_it_arrived() throws JSONException {
        final Enrichment enrichment = enrich(APP1_HEARING, id -> found(PROGRESSION_LISTED));

        assertThat(asked).containsExactly(UUID.fromString(APP1));
        assertThat(enrichment.applied()).isFalse();
        assertSameContent(APP1_HEARING, enrichment);
        assertThat(enrichment.parsedCopy()).isEqualTo(resource(APP1_HEARING));
    }

    @Test
    void app1_with_an_empty_answer_should_stay_as_it_arrived() throws JSONException {
        final Enrichment enrichment = enrich(APP1_HEARING, id -> new ApplicationAnswer.NotFound());

        assertThat(asked).containsExactly(UUID.fromString(APP1));
        assertThat(enrichment.applied()).isFalse();
        assertSameContent(APP1_HEARING, enrichment);
    }

    @Test
    void several_applications_should_each_be_enriched_as_results_enriches_it() throws JSONException {
        final Map<UUID, String> answers = Map.of(UUID.fromString(APP1), APP1_FINALISED,
                UUID.fromString(APP2), APP2_FINALISED, UUID.fromString(LISTED), PROGRESSION_LISTED);

        final Enrichment enrichment = enrich("multi_application_hearing.json", id -> found(answers.get(id)));

        assertThat(asked).containsExactly(UUID.fromString(APP1), UUID.fromString(APP2), UUID.fromString(LISTED));
        assertThat(enrichment.applied()).isTrue();
        assertSameContent("multi_application_hearing_enriched.json", enrichment);
        assertThat(application(enrichment.tree(), 0).propertyNames()).last().isEqualTo(JUDICIAL_RESULTS);
        assertThat(application(enrichment.tree(), 1).propertyNames()).last().isEqualTo(JUDICIAL_RESULTS);
        assertThat(application(enrichment.tree(), 2).has(JUDICIAL_RESULTS)).isFalse();
    }

    /** The store's fake port: one call per id the scan names, in order, as results' fake answers any id. */
    private Enrichment enrich(final String share, final Function<UUID, ApplicationAnswer> progression) {
        final String text = resource(share);
        final JsonNode body = parser.readTree(text);
        final Map<UUID, ApplicationAnswer> answers = new LinkedHashMap<>();
        for (final UUID id : enricher.scan(body).lookups()) {
            asked.add(id);
            answers.put(id, progression.apply(id));
        }
        return enricher.enrich(text, body, answers);
    }

    private void assertSameContent(final String expected, final Enrichment enrichment) throws JSONException {
        JSONAssert.assertEquals(resource(expected), enrichment.parsedCopy(), JSONCompareMode.STRICT);
        // A boolean, so a failure prints no hearing content.
        assertThat(parser.readTree(enrichment.parsedCopy()).equals(enrichment.tree()))
                .as("the parsed copy reads back as the enriched tree").isTrue();
    }

    private ApplicationAnswer found(final String file) {
        return new ApplicationAnswer.Found(parser.readTree(resource(file)));
    }

    private static JsonNode application(final JsonNode body, final int index) {
        return body.path("hearing").path("courtApplications").get(index);
    }

    private static String resource(final String file) {
        try (InputStream stream = Objects.requireNonNull(
                ApplicationResultsParityTest.class.getResourceAsStream("/progression/" + file), file)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
