package uk.gov.hmcts.cp.resultsstore.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.resultsstore.adapter.progression.NoRedirectRequestFactory;
import uk.gov.hmcts.cp.resultsstore.adapter.progression.ProgressionApplicationClient;
import uk.gov.hmcts.cp.resultsstore.application.ProgressionApplications;

/**
 * The progression client (specs/002-enrichment/contracts/configuration.md), built only while the
 * subscription and enrichment are both on. Then the base URL and the system user id must be set:
 * a missing value stops the service rather than sending the call to its own port. With either switch
 * off nothing is built and both may be blank.
 *
 * <p>Wiring only: the classification lives in {@link ProgressionApplicationClient}, where the coverage
 * gate measures it.
 */
@Configuration(proxyBeanMethods = false)
public class ProgressionConfig {

    private static final String ON = "true";

    /**
     * The client's transport, a bean of its own so the context closes it on shutdown: connect and read
     * timeouts from the settings, and the read timeout also as the whole-response deadline.
     *
     * @param progression the progression settings, their shape already checked
     * @return the request factory
     */
    @Bean
    @ConditionalOnProperty(name = "resultsstore.publicevents.enabled", havingValue = ON)
    @ConditionalOnProperty(name = "resultsstore.enrichment.enabled", havingValue = ON, matchIfMissing = true)
    public NoRedirectRequestFactory progressionRequestFactory(final ProgressionProperties progression) {
        return new NoRedirectRequestFactory(progression.connectTimeout(), progression.readTimeout(),
                progression.readTimeout());
    }

    /**
     * Builds the client over the request factory.
     *
     * @param progression    the progression settings, their shape already checked
     * @param requestFactory the transport, from {@link #progressionRequestFactory}
     * @param mapper         the application's mapper, from which the client derives its reader
     * @return the client
     * @throws IllegalArgumentException naming the property, never its value, when the base URL or the
     *                                  system user id is blank
     */
    @Bean
    @ConditionalOnProperty(name = "resultsstore.publicevents.enabled", havingValue = ON)
    @ConditionalOnProperty(name = "resultsstore.enrichment.enabled", havingValue = ON, matchIfMissing = true)
    public ProgressionApplications progressionApplications(final ProgressionProperties progression,
            final NoRedirectRequestFactory requestFactory, final ObjectMapper mapper) {
        required(ProgressionProperties.BASE_URL, progression.baseUrl());
        required(ProgressionProperties.SYSTEM_USER_ID, progression.systemUserId());
        final RestClient restClient = RestClient.builder()
                .baseUrl(progression.baseUrl())
                .requestFactory(requestFactory)
                .build();
        return new ProgressionApplicationClient(restClient, progression.systemUserId(), mapper);
    }

    private static void required(final String name, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set when resultsstore.enrichment.enabled is true");
        }
    }
}
