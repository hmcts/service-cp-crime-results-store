package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.core.Ordered;
import uk.gov.hmcts.cp.resultsstore.api.BoundedErrorAttributes;
import uk.gov.hmcts.cp.resultsstore.api.BoundedErrorController;
import uk.gov.hmcts.cp.resultsstore.application.RefusalObserver;
import uk.gov.hmcts.cp.resultsstore.filters.ActionHeaderFilter;
import uk.gov.hmcts.cp.resultsstore.filters.UnsupportedContentTypeFilter;

/**
 * The web edge's wiring: the filter registrations at their orders, the bounded error beans, the refusal
 * observer, and the authorisation-required check (FR-050, D-AUTHZ-REQUIRED).
 */
@DisplayName("the read API's web wiring")
class ApiWebConfigTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(ApiWebConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void authz_off_without_the_test_profile_should_stop_the_service_naming_authz_http_enabled() {
        runner.withPropertyValues("authz.http.enabled=false").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("authz.http.enabled");
        });
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("authz.http.enabled=yes").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void authz_off_with_the_test_profile_should_start() {
        runner.withPropertyValues("authz.http.enabled=false", "spring.profiles.active=test")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("authz.http.enabled=true").run(context -> assertThat(context).hasNotFailed());
        // The library's own switch is @ConditionalOnProperty(havingValue = "true"), which ignores case.
        runner.withPropertyValues("authz.http.enabled=TRUE").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void the_filters_should_be_registered_at_their_orders() {
        runner.withPropertyValues("authz.http.enabled=true").run(context -> {
            final List<FilterRegistrationBean<?>> registrations = context.getBeansOfType(FilterRegistrationBean.class)
                    .values().stream()
                    .<FilterRegistrationBean<?>>map(registration -> registration)
                    .toList();
            final Map<Class<?>, Integer> orders = registrations.stream()
                    .collect(Collectors.toMap(registration -> registration.getFilter().getClass(),
                            FilterRegistrationBean::getOrder));

            assertThat(orders).containsOnly(Map.entry(ActionHeaderFilter.class, Ordered.HIGHEST_PRECEDENCE),
                    Map.entry(UnsupportedContentTypeFilter.class, Ordered.HIGHEST_PRECEDENCE + 40));
            assertThat(registrations).allSatisfy(registration -> {
                final Collection<String> patterns = registration.getUrlPatterns();
                assertThat(patterns).containsExactly("/*");
            });
            assertThat(context).hasSingleBean(RefusalObserver.class)
                    .getBean(RefusalObserver.class).isInstanceOf(MicrometerRefusalObserver.class);
            assertThat(context).getBean(ErrorAttributes.class).isInstanceOf(BoundedErrorAttributes.class);
            assertThat(context).getBean(ErrorController.class).isInstanceOf(BoundedErrorController.class);
            assertThat(context).hasSingleBean(TomcatEdgeCustomizer.class);
        });
    }
}
