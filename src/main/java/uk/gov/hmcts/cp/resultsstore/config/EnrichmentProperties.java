package uk.gov.hmcts.cp.resultsstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code resultsstore.enrichment.*} (specs/002-enrichment/contracts/configuration.md).
 *
 * @param enabled whether shares are enriched from progression; when false no progression call is made
 *                and every share is stored as it arrived
 */
@ConfigurationProperties("resultsstore.enrichment")
public record EnrichmentProperties(@DefaultValue("true") boolean enabled) {
}
