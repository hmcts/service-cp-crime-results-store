package uk.gov.hmcts.cp.resultsstore.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import uk.gov.moj.cpp.authz.drools.Action;
import uk.gov.moj.cpp.authz.drools.Outcome;
import uk.gov.moj.cpp.authz.http.providers.UserAndGroupProvider;

class ResultsStoreRulesTest {

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

    @Test
    void the_rules_should_compile() {
        assertThat(builder.getResults().getMessages(Message.Level.ERROR)).isEmpty();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // disposed in the finally block
    void no_action_should_be_allowed_yet() {
        final UserAndGroupProvider everyone = mock(UserAndGroupProvider.class);
        when(everyone.isMemberOfAnyOfTheSuppliedGroups(any(Action.class), any(String[].class))).thenReturn(true);
        final KieSession session = container.newKieSession();
        try {
            session.setGlobal("userAndGroupProvider", everyone);
            final Outcome outcome = new Outcome();
            session.insert(outcome);
            session.insert(new Action("results-store.anything", Map.of()));
            session.fireAllRules();

            assertThat(outcome.isSuccess()).isFalse();
        } finally {
            session.dispose();
        }
    }
}
