package uk.gov.hmcts.cp.resultsstore.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.anotherRoutesPath;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.samplePath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.definition.KiePackage;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;
import uk.gov.moj.cpp.authz.drools.Action;
import uk.gov.moj.cpp.authz.drools.Outcome;
import uk.gov.moj.cpp.authz.http.providers.UserAndGroupProvider;

/**
 * The read API's allow rules, compiled with KIE as the library compiles them, and fired with the
 * {@code Action(name, {method, path})} fact the library builds (research R3).
 */
@DisplayName("results store allow rules")
class ResultsStoreRulesTest {

    private static final String SYSTEM_USERS = "System Users";

    private static final String SECOND_LINE_SUPPORT = "Second Line Support";

    private static final String SAMPLE_SHARE_ID = "6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b";

    private static KieBuilder builder;

    private static KieContainer container;

    @BeforeAll
    static void compile() {
        final KieServices services = KieServices.Factory.get();
        final KieFileSystem files = services.newKieFileSystem();
        files.write(services.getResources()
                .newClassPathResource("acl/results-store-rules.drl")
                .setResourceType(ResourceType.DRL));
        builder = services.newKieBuilder(files).buildAll();
        container = services.newKieContainer(services.getRepository().getDefaultReleaseId());
    }

    /** A provider whose caller is in exactly the given groups, recording every group list a rule asked about. */
    private static final class Caller implements UserAndGroupProvider {

        private final Set<String> groups;

        private final List<List<String>> asked = new ArrayList<>();

        Caller(final String... groups) {
            this.groups = Set.of(groups);
        }

        @Override
        public boolean isMemberOfAnyOfTheSuppliedGroups(final Action action, final String... supplied) {
            asked.add(List.of(supplied));
            return Arrays.stream(supplied).anyMatch(groups::contains);
        }

        @Override
        public boolean hasPermission(final Action action, final String... permissions) {
            return false;
        }
    }

    private record Fired(boolean allowed, int rulesFired) {
    }

    @SuppressWarnings("PMD.CloseResource") // disposed in the finally block
    private static Fired fire(final UserAndGroupProvider provider, final String action, final String method,
                              final String path) {
        final KieSession session = container.newKieSession();
        try {
            session.setGlobal("userAndGroupProvider", provider);
            final Outcome outcome = new Outcome();
            session.insert(outcome);
            session.insert(new Action(action, Map.of("method", method, "path", path)));
            final int fired = session.fireAllRules();
            return new Fired(outcome.isSuccess(), fired);
        } finally {
            session.dispose();
        }
    }

    @Test
    void the_rules_should_compile() {
        assertThat(builder.getResults().getMessages(Message.Level.ERROR)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void every_read_action_should_be_allowed_for_system_users(final ApiRoute route) {
        assertThat(fire(new Caller(SYSTEM_USERS), route.action(), "GET", samplePath(route)).allowed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void every_read_action_should_be_allowed_for_second_line_support(final ApiRoute route) {
        assertThat(fire(new Caller(SECOND_LINE_SUPPORT), route.action(), "GET", samplePath(route)).allowed())
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void every_read_action_should_be_refused_for_a_caller_in_neither_group(final ApiRoute route) {
        assertThat(fire(new Caller("Other Group"), route.action(), "GET", samplePath(route)).allowed()).isFalse();
        assertThat(fire(new Caller(), route.action(), "GET", samplePath(route)).allowed()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void the_right_action_name_with_another_method_should_be_refused(final ApiRoute route) {
        final Caller both = new Caller(SYSTEM_USERS, SECOND_LINE_SUPPORT);

        assertThat(fire(both, route.action(), "POST", samplePath(route)).allowed()).isFalse();
        assertThat(fire(both, route.action(), "get", samplePath(route)).allowed()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void the_right_action_name_with_another_routes_path_should_be_refused(final ApiRoute route) {
        final Caller both = new Caller(SYSTEM_USERS, SECOND_LINE_SUPPORT);

        assertThat(fire(both, route.action(), "GET", anotherRoutesPath(route)).allowed()).isFalse();
        assertThat(fire(both, route.action(), "GET", samplePath(route) + "/extra").allowed()).isFalse();
        assertThat(fire(both, route.action(), "GET", "/prefix" + samplePath(route)).allowed()).isFalse();
    }

    /** The arrived text's rule is withdrawn with its route (spec 005 FR-007): no group is admitted. */
    @Test
    void the_arrived_payload_action_should_be_refused_for_both_groups() {
        final String action = "results-store.get-share-arrived-payload";
        final String arrived = "/results-store/v1/shares/" + SAMPLE_SHARE_ID + "/payload/arrived";

        assertThat(fire(new Caller(SYSTEM_USERS), action, "GET", arrived).allowed()).isFalse();
        assertThat(fire(new Caller(SECOND_LINE_SUPPORT), action, "GET", arrived).allowed()).isFalse();
        assertThat(fire(new Caller(SYSTEM_USERS, SECOND_LINE_SUPPORT), action, "GET", arrived).allowed()).isFalse();
    }

    @Test
    void an_unknown_action_should_be_refused() {
        final Caller both = new Caller(SYSTEM_USERS, SECOND_LINE_SUPPORT);

        assertThat(fire(both, "results-store.anything", "GET", "/results-store/v1/anything").allowed()).isFalse();
        assertThat(fire(both, "GET /results-store/v1/shares", "GET", "/results-store/v1/shares").allowed())
                .isFalse();
    }

    @Test
    void there_should_be_exactly_one_rule_per_action() {
        final long rules = container.getKieBase().getKiePackages().stream()
                .map(KiePackage::getRules)
                .mapToLong(Collection::size)
                .sum();

        assertThat(rules).isEqualTo(ApiRoute.values().length);
        assertThat(Arrays.stream(ApiRoute.values())
                .map(route -> fire(new Caller(SYSTEM_USERS), route.action(), "GET", samplePath(route)).rulesFired()))
                .containsOnly(1);
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void every_rule_should_name_exactly_the_two_groups(final ApiRoute route) {
        final Caller caller = new Caller(SYSTEM_USERS);

        fire(caller, route.action(), "GET", samplePath(route));

        assertThat(caller.asked).containsExactly(List.of(SYSTEM_USERS, SECOND_LINE_SUPPORT));
    }
}
