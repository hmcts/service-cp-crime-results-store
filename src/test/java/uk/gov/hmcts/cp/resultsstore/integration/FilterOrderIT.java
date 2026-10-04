package uk.gov.hmcts.cp.resultsstore.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.filter.audit.AuditFilter;
import uk.gov.hmcts.cp.resultsstore.filters.ActionHeaderFilter;
import uk.gov.hmcts.cp.resultsstore.filters.UnsupportedContentTypeFilter;
import uk.gov.hmcts.cp.resultsstore.support.EmbeddedBrokerSupport;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.moj.cpp.authz.http.HttpAuthzFilter;

/**
 * The filter order with authorisation and audit both on (research R2): the action filter, then the
 * authorisation library, then the {@code 415} guard, then the audit library. Read from the registrations
 * Boot builds the servlet filter chain from, in the order it would register them.
 */
@SpringBootTest(properties = {"authz.http.enabled=true", "audit.http.enabled=true", "cp.audit.enabled=true"})
@ActiveProfiles("test")
@DisplayName("filter order")
class FilterOrderIT {

    private static final WireMockServer USERSGROUPS =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    private static EmbeddedBrokerSupport auditBroker;

    @Autowired
    private ListableBeanFactory beanFactory;

    @DynamicPropertySource
    static void storeAuthzAndAudit(final DynamicPropertyRegistry registry) throws Exception {
        if (!USERSGROUPS.isRunning()) {
            USERSGROUPS.start();
        }
        if (auditBroker == null) {
            auditBroker = EmbeddedBrokerSupport.start("filter-order-audit", 2);
        }
        PostgresTestSupport.register(registry);
        registry.add("authz.http.identity-url-template", () -> "http://localhost:" + USERSGROUPS.port()
                + "/usersgroups-query-api/query/api/rest/usersgroups/users/logged-in-user/permissions");
        registry.add("cp.audit.hosts", () -> "localhost");
        registry.add("cp.audit.port", () -> URI.create(auditBroker.url()).getPort());
    }

    private record Registered(Class<?> filter, int order) {
    }

    @Test
    void the_action_filter_should_precede_authz_which_should_precede_the_415_guard_which_should_precede_audit() {
        final List<Registered> chain = StreamSupport.stream(new ServletContextInitializerBeans(beanFactory)
                        .spliterator(), false)
                .filter(FilterRegistrationBean.class::isInstance)
                .map(FilterRegistrationBean.class::cast)
                .map(registration -> new Registered(registration.getFilter().getClass(), registration.getOrder()))
                .filter(registered -> List.of(ActionHeaderFilter.class, HttpAuthzFilter.class,
                        UnsupportedContentTypeFilter.class, AuditFilter.class).contains(registered.filter()))
                .toList();

        assertThat(chain).extracting(Registered::filter).containsExactly(ActionHeaderFilter.class,
                HttpAuthzFilter.class, UnsupportedContentTypeFilter.class, AuditFilter.class);
        assertThat(chain).extracting(Registered::order).isSorted().doesNotHaveDuplicates();
    }
}
