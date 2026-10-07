package uk.gov.hmcts.cp.resultsstore.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.HEARING_DAY;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.HEARING_ID;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.SHARE_ID;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.needsStoredAfterSeq;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.samplePath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.PathContainer;
import uk.gov.hmcts.cp.resultsstore.domain.ReadEndpoint;

@DisplayName("the read API's route table")
class ApiRouteTest {

    private static final String STORED_AFTER_SEQ = "storedAfterSeq";

    private static PathContainer path(final String value) {
        return PathContainer.parsePath(value);
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void each_template_should_match_its_sample_path_and_no_other_route(final ApiRoute route) {
        final PathContainer sample = path(samplePath(route));

        assertThat(ApiRoute.resolve("GET", sample, name -> needsStoredAfterSeq(route))).contains(route);
        assertThat(Arrays.stream(ApiRoute.values()).filter(candidate -> candidate.matches(sample)))
                .allSatisfy(candidate -> assertThat(candidate.template()).isEqualTo(route.template()))
                .contains(route);
    }

    @Test
    void pull_and_search_should_be_told_apart_by_stored_after_seq() {
        final PathContainer shares = path("/results-store/v1/shares");

        assertThat(ApiRoute.resolve("GET", shares, STORED_AFTER_SEQ::equals)).contains(ApiRoute.PULL_SHARES);
        assertThat(ApiRoute.resolve("GET", shares, name -> false)).contains(ApiRoute.SEARCH_SHARES);
    }

    @Test
    void stored_after_seq_should_be_looked_up_only_after_the_method_and_path_match() {
        final List<String> asked = new ArrayList<>();

        final Optional<ApiRoute> post = ApiRoute.resolve("POST", path("/results-store/v1/shares"), name -> {
            asked.add(name);
            return true;
        });
        final Optional<ApiRoute> unknown = ApiRoute.resolve("GET", path("/results-store/v1/other"), name -> {
            asked.add(name);
            return true;
        });
        final Optional<ApiRoute> share = ApiRoute.resolve("GET", path(samplePath(ApiRoute.GET_SHARE)), name -> {
            asked.add(name);
            return true;
        });

        assertThat(post).isEmpty();
        assertThat(unknown).isEmpty();
        assertThat(share).contains(ApiRoute.GET_SHARE);
        assertThat(asked).isEmpty();
    }

    /** Each form Spring MVC would not route to a template (or, for {@code ;params}, one the service refuses). */
    @ParameterizedTest
    @ValueSource(strings = {
        "/results-store/v1/shares/",
        "/results-store/v1/shares;x=1",
        "/results-store/v1/shares;",
        "/results-store/v1/shares/" + SHARE_ID + ";v=1",
        "/results-store/v1/shares/" + SHARE_ID + "/payload;v=1",
        "/results-store/v1//shares",
        "//results-store/v1/shares",
        "/results-store/v1/shares//payload",
        "/RESULTS-STORE/v1/shares",
        "/results-store/v1/Shares",
        "/results-store/V1/shares/" + SHARE_ID,
        "/results-store/v1/shares/" + SHARE_ID + "/Payload",
        "/results-store/v1/shares/" + SHARE_ID + "/payload/",
        "/results-store/v1/shares/" + SHARE_ID + "/payload/arrived",
        "/results-store/v1/shares/" + SHARE_ID + "/payload/arrived/",
        "/results-store/v1/shares/" + SHARE_ID + "/payload/Arrived",
        "/results-store/v1/shares/" + SHARE_ID + "/payload/arrived;v=1",
        "/results-store/v1/hearings/" + HEARING_ID + "/days/" + HEARING_DAY + "/shares/",
        "/results-store/v1/hearings/" + HEARING_ID + "/days/" + HEARING_DAY + "/Shares"
    })
    void a_trailing_slash_semicolon_parameter_double_slash_or_upper_case_path_should_match_nothing(
            final String value) {
        assertThat(ApiRoute.allowedMethods(path(value))).isEmpty();
        assertThat(ApiRoute.resolve("GET", path(value), name -> true)).isEmpty();
        assertThat(Arrays.stream(ApiRoute.values()).filter(route -> route.matches(path(value)))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void allowed_methods_should_be_get_for_a_known_path_and_empty_for_an_unknown_one(final ApiRoute route) {
        assertThat(route.method()).isEqualTo("GET");
        assertThat(ApiRoute.allowedMethods(path(samplePath(route)))).containsExactly("GET");
        assertThat(ApiRoute.allowedMethods(path("/results-store/v1/anything"))).isEmpty();
        assertThat(ApiRoute.allowedMethods(path("/results-store/v1/shares/" + SHARE_ID + "/payload/raw")))
                .isEmpty();
        assertThat(ApiRoute.allowedMethods(path("/"))).isEmpty();
    }

    @Test
    void every_action_should_be_kebab_verb_noun_with_the_results_store_prefix() {
        assertThat(Arrays.stream(ApiRoute.values()).map(ApiRoute::action))
                .allSatisfy(action -> assertThat(action).matches("results-store\\.[a-z]+(-[a-z]+)+"))
                .doesNotHaveDuplicates()
                .containsExactly("results-store.pull-shares", "results-store.search-shares",
                        "results-store.get-share", "results-store.get-share-payload",
                        "results-store.list-hearing-day-shares");
    }

    /** The {@code endpoint} tag each route's read meters carry (contracts/metrics.md). */
    @Test
    void every_route_should_name_its_endpoint_tag() {
        assertThat(Arrays.stream(ApiRoute.values()).map(ApiRoute::endpoint)).containsExactly(ReadEndpoint.PULL,
                ReadEndpoint.SEARCH, ReadEndpoint.SHARE, ReadEndpoint.PAYLOAD, ReadEndpoint.DAY_VERSIONS);
    }
}
