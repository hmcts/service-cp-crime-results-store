package uk.gov.hmcts.cp.resultsstore.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.application.ShareQueries;
import uk.gov.hmcts.cp.resultsstore.application.ShareReadService;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcShareQueries;

/**
 * The read API's settings and beans (specs/003-read-api research R1, R6, R18), whatever
 * {@code resultsstore.publicevents.enabled} says: a pod with the subscription off still serves reads.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReadApiProperties.class, IntakeProperties.class})
public class ReadApiConfig {

    /** The longest lag: a pull held back further than this is no feed (D-LAG-VALUE, E3). */
    private static final Duration MOST_LAG = Duration.ofMinutes(10);

    /** The PostgreSQL driver's socket timeout, in seconds; 0 or absent means none. */
    private static final String SOCKET_TIMEOUT = "spring.datasource.hikari.data-source-properties.socketTimeout";

    private static final String VISIBILITY_LAG = "resultsstore.read.pull.visibility-lag";

    private static final String INTAKE_SUM = "resultsstore.intake.store.transaction-timeout + 2 x "
            + "resultsstore.intake.store.statement-timeout + resultsstore.intake.store.idle-in-transaction-timeout";

    private final VisibilityLag lag;

    /**
     * Works out the effective lag.
     *
     * @param read        the read settings
     * @param intake      the intake settings the lag is derived from
     * @param environment the environment, for the datasource's socket timeout
     */
    public ReadApiConfig(final ReadApiProperties read, final IntakeProperties intake, final Environment environment) {
        final Duration bound = intake.store().visibilityBound();
        final Duration set = read.pull().visibilityLag();
        if (set == null) {
            // Derived: only the intake values can make it too long, so the message names them.
            Rules.atMost(INTAKE_SUM + " (the derived " + VISIBILITY_LAG + ")", bound, "10 minutes", MOST_LAG);
        } else {
            Rules.atLeast(VISIBILITY_LAG, set, INTAKE_SUM, bound);
            Rules.atMost(VISIBILITY_LAG, set, "10 minutes", MOST_LAG);
        }
        final Integer socketSeconds = environment.getProperty(SOCKET_TIMEOUT, Integer.class);
        if (socketSeconds != null && socketSeconds > 0
                && read.statementTimeout().compareTo(Duration.ofSeconds(socketSeconds)) >= 0) {
            throw new IllegalStateException("resultsstore.read.statement-timeout must be below " + SOCKET_TIMEOUT);
        }
        this.lag = new VisibilityLag(set == null ? bound : set);
    }

    /** The effective lag, for the read service and for intake's overrun threshold. */
    @Bean
    public VisibilityLag visibilityLag() {
        return lag;
    }

    /** The read queries, over a template of their own with the read statement timeout. */
    @Bean
    public JdbcShareQueries jdbcShareQueries(final DataSource dataSource, final ReadApiProperties read) {
        return new JdbcShareQueries(readTemplate(dataSource, read.statementTimeout()));
    }

    /** The read meters. */
    @Bean
    public ReadObserver readObserver(final MeterRegistry registry) {
        return new MicrometerReadObserver(registry);
    }

    /** The read service. */
    @Bean
    public ShareReadService shareReadService(final ShareQueries queries, final ReadObserver observer) {
        return new ShareReadService(queries, observer, lag.value());
    }

    /**
     * The read template: not a bean, so Boot's own {@code JdbcTemplate} (and the {@code JdbcClient} intake uses)
     * stays as it is.
     */
    /* default */ static JdbcTemplate readTemplate(final DataSource dataSource, final Duration statementTimeout) {
        final JdbcTemplate template = new JdbcTemplate(dataSource);
        // Whole seconds, rounded up so a part second never becomes no timeout at all.
        final long millis = statementTimeout.toMillis();
        template.setQueryTimeout(Math.toIntExact(Math.max(1, (millis + 999) / 1000)));
        return template;
    }
}
