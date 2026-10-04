package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code resultsstore.progression.*} (specs/002-enrichment/contracts/configuration.md).
 *
 * <p>The base URL and the system user id have no default. Their shape is checked here when they are
 * set; whether they may be blank depends on {@code resultsstore.enrichment.enabled}, so that check runs
 * where the client is built. Neither is logged, so {@link #toString()} leaves both out.
 *
 * @param baseUrl        progression's host: an absolute http(s) URL with no user info, path, query or
 *                       fragment
 * @param systemUserId   the store's own system user, a canonical UUID, sent as {@code CJSCPPUID}
 * @param connectTimeout the connect timeout, 1 s to 30 s
 * @param readTimeout    the read timeout, 1 s to 60 s; also the whole-response deadline
 */
@ConfigurationProperties("resultsstore.progression")
public record ProgressionProperties(String baseUrl, String systemUserId,
        @DefaultValue("5s") Duration connectTimeout, @DefaultValue("10s") Duration readTimeout) {

    /** The property holding the base URL, as failures name it. */
    public static final String BASE_URL = "resultsstore.progression.base-url";

    /** The property holding the system user id, as failures name it. */
    public static final String SYSTEM_USER_ID = "resultsstore.progression.system-user-id";

    /** Checks the rules of contracts/configuration.md that need no other setting. */
    public ProgressionProperties {
        Rules.within("resultsstore.progression.connect-timeout", connectTimeout, Duration.ofSeconds(1),
                Duration.ofSeconds(30));
        Rules.within("resultsstore.progression.read-timeout", readTimeout, Duration.ofSeconds(1),
                Duration.ofSeconds(60));
        if (!isBlank(baseUrl)) {
            Rules.absoluteHttpUrl(BASE_URL, baseUrl);
        }
        if (!isBlank(systemUserId)) {
            Rules.uuid(SYSTEM_USER_ID, systemUserId);
        }
    }

    /** Leaves out the system user id and the base URL, an internal connection detail. */
    @Override
    public String toString() {
        return "ProgressionProperties[connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
