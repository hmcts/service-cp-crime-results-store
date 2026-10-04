package uk.gov.hmcts.cp.resultsstore.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import uk.gov.hmcts.cp.resultsstore.api.BoundedErrorAttributes;
import uk.gov.hmcts.cp.resultsstore.api.BoundedErrorController;
import uk.gov.hmcts.cp.resultsstore.application.RefusalObserver;
import uk.gov.hmcts.cp.resultsstore.filters.ActionHeaderFilter;
import uk.gov.hmcts.cp.resultsstore.filters.PayloadBodyFreeAuditPayloadGenerationService;
import uk.gov.hmcts.cp.resultsstore.filters.UnsupportedContentTypeFilter;

/**
 * The read API's web edge (research R2, R13, R18).
 *
 * <ul>
 *   <li>The action filter first ({@code HIGHEST_PRECEDENCE}), before {@code cp-auth-rules-filter}
 *       ({@code +30}); the {@code 415} guard after it ({@code +40}) and before the audit library's filter
 *       ({@code +50}, its own {@code @Order}).</li>
 *   <li>The bounded {@code /error} attributes and the service's own error controller. The controller is a bean
 *       here, behind its own URL mapping, rather than a component-scanned {@code @Controller}, so
 *       {@code @WebMvcTest} slices do not pick it up.</li>
 *   <li>The refusal counter.</li>
 *   <li>The connector policy and the host's problem error report ({@link TomcatEdgeCustomizer}).</li>
 *   <li>The authorisation-required check (FR-050, D-AUTHZ-REQUIRED): outside the {@code test} profile the
 *       service refuses to start unless {@code authz.http.enabled} is {@code true}, the value the library's
 *       own switch answers to (case ignored).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ApiWebConfig {

    /** The authorisation library's switch. */
    private static final String AUTHZ_ENABLED = "authz.http.enabled";

    private static final String TEST_PROFILE = "test";

    private static final String ALL_PATHS = "/*";

    private static final String ERROR_PATH = "/error";

    private static final int UNSUPPORTED_CONTENT_TYPE_ORDER = Ordered.HIGHEST_PRECEDENCE + 40;

    /**
     * Checks that authorisation is on outside the {@code test} profile.
     *
     * @param environment the environment
     */
    public ApiWebConfig(final Environment environment) {
        if (!"true".equalsIgnoreCase(environment.getProperty(AUTHZ_ENABLED))
                && !environment.acceptsProfiles(Profiles.of(TEST_PROFILE))) {
            throw new IllegalStateException(AUTHZ_ENABLED + " must be true: the read API is default-deny and "
                    + "is served only behind cp-auth-rules-filter (off only in the test profile)");
        }
    }

    /** Counts the refusals made before the audit filter. */
    @Bean
    public RefusalObserver refusalObserver(final MeterRegistry registry) {
        return new MicrometerRefusalObserver(registry);
    }

    /** The action filter, first of all. */
    @Bean
    public FilterRegistrationBean<ActionHeaderFilter> actionHeaderFilterRegistration(
            final RefusalObserver refusalObserver) {
        final FilterRegistrationBean<ActionHeaderFilter> registration =
                new FilterRegistrationBean<>(new ActionHeaderFilter(refusalObserver));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns(ALL_PATHS);
        return registration;
    }

    /** The {@code 415} guard, after authorisation and before audit. */
    @Bean
    public FilterRegistrationBean<UnsupportedContentTypeFilter> unsupportedContentTypeFilterRegistration(
            final RefusalObserver refusalObserver) {
        final FilterRegistrationBean<UnsupportedContentTypeFilter> registration =
                new FilterRegistrationBean<>(new UnsupportedContentTypeFilter(refusalObserver));
        registration.setOrder(UNSUPPORTED_CONTENT_TYPE_ORDER);
        registration.addUrlPatterns(ALL_PATHS);
        return registration;
    }

    /**
     * The connector policy and the host's problem error report. The refusal observer is looked up when a
     * refusal is counted, not when the web server is built, so the meter registry is not created early.
     */
    @Bean
    public TomcatEdgeCustomizer tomcatEdgeCustomizer(final ObjectProvider<RefusalObserver> refusalObserver) {
        return new TomcatEdgeCustomizer(reason -> refusalObserver.getObject().refused(reason));
    }

    /** The bounded {@code /error} attributes; Boot's {@code DefaultErrorAttributes} backs off. */
    @Bean
    public BoundedErrorAttributes errorAttributes() {
        return new BoundedErrorAttributes();
    }

    /** The service's {@code /error} page; Boot's {@code BasicErrorController} backs off. */
    @Bean
    public BoundedErrorController errorController(final ErrorAttributes errorAttributes,
                                                  final RefusalObserver refusalObserver) {
        return new BoundedErrorController(errorAttributes, refusalObserver);
    }

    /**
     * The audit library's event builder with the payload endpoints' response body replaced by a marker (D-AUDIT
     * option 4, FR-052). Only with the audit transport on; the library's own builder backs off
     * ({@code @ConditionalOnMissingBean}).
     */
    @Bean
    @ConditionalOnProperty(name = "cp.audit.enabled", havingValue = "true")
    public PayloadBodyFreeAuditPayloadGenerationService payloadBodyFreeAuditPayloadGenerationService(
            @Qualifier("auditObjectMapper") final ObjectMapper auditObjectMapper) {
        return new PayloadBodyFreeAuditPayloadGenerationService(auditObjectMapper);
    }

    /** Maps {@code /error}, every method, to the error controller, ahead of every other mapping. */
    @Bean
    public SimpleUrlHandlerMapping errorHandlerMapping(final BoundedErrorController errorController) {
        return new SimpleUrlHandlerMapping(Map.of(ERROR_PATH, errorController), Ordered.HIGHEST_PRECEDENCE);
    }
}
