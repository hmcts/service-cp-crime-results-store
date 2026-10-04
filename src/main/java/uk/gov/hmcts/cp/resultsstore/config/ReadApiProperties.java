package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code resultsstore.read.*} (specs/003-read-api contracts/configuration.md). The rules that span other
 * settings (the lag against the intake timeouts, the statement timeout against the socket timeout) are checked
 * by {@link ReadApiConfig}.
 *
 * @param pull             the pull's settings
 * @param statementTimeout the read queries' JDBC query timeout
 */
@ConfigurationProperties("resultsstore.read")
public record ReadApiProperties(@DefaultValue Pull pull, @DefaultValue("5s") Duration statementTimeout) {

    /** Checks the rule that needs no other setting: the statement timeout is above zero. */
    public ReadApiProperties {
        Rules.positive("resultsstore.read.statement-timeout", statementTimeout);
    }

    /**
     * {@code resultsstore.read.pull.*}.
     *
     * @param visibilityLag the pull's visibility lag; {@code null} (unset or empty) for the value derived from
     *                      the intake timeouts
     */
    public record Pull(Duration visibilityLag) {

        /** Checks a set lag is above zero. */
        public Pull {
            if (visibilityLag != null) {
                Rules.positive("resultsstore.read.pull.visibility-lag", visibilityLag);
            }
        }
    }
}
