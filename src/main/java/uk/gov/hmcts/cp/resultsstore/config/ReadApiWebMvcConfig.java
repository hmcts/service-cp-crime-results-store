package uk.gov.hmcts.cp.resultsstore.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gov.hmcts.cp.resultsstore.api.InstantFormat;
import uk.gov.hmcts.cp.resultsstore.api.ReadMetricsInterceptor;
import uk.gov.hmcts.cp.resultsstore.api.ShareParametersInterceptor;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;

/**
 * The read API's Spring MVC settings (research R16, R17, R23 C3): the two read interceptors on the read paths,
 * the metrics one first, so a parameter refusal is counted ({@code bad_request}); and the six-digit instant
 * format on the JSON mapper. Kept apart from {@link ApiWebConfig}, so a {@code @WebMvcTest} slice of the
 * controller picks these up (a {@code WebMvcConfigurer}) without the filters and the error page.
 */
@Configuration(proxyBeanMethods = false)
public class ReadApiWebMvcConfig implements WebMvcConfigurer {

    /** The read API's paths. */
    private static final String READ_PATHS = "/results-store/v1/**";

    private final ObjectProvider<ReadObserver> observer;

    /**
     * Creates the settings.
     *
     * @param observer the read meters, looked up when the interceptors are registered
     */
    public ReadApiWebMvcConfig(final ObjectProvider<ReadObserver> observer) {
        this.observer = observer;
    }

    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(new ReadMetricsInterceptor(observer.getObject(), System::nanoTime))
                .addPathPatterns(READ_PATHS);
        registry.addInterceptor(new ShareParametersInterceptor()).addPathPatterns(READ_PATHS);
    }

    /** Every instant the read API writes: UTC, six fraction digits. */
    @Bean
    public JsonMapperBuilderCustomizer instantFormatCustomizer() {
        return builder -> builder.addModule(InstantFormat.module());
    }
}
