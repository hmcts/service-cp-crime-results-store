package uk.gov.hmcts.cp.resultsstore.config;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The pool backstop (specs/003-read-api FR-062; contracts/configuration.md *Pool backstop*): every connection the
 * Hikari pool opens starts with {@code SET statement_timeout = '<n>ms'}, n being
 * {@code resultsstore.intake.store.statement-timeout} in milliseconds, read through the same binding as
 * {@link IntakeProperties}, so the two cannot differ. Built in Java rather than written in YAML from the same
 * variable, because Spring and PostgreSQL read duration strings differently ({@code 1m}, {@code PT10S}), and an
 * init SQL PostgreSQL rejects would fail every connection. A separately set
 * {@code spring.datasource.hikari.connection-init-sql} stops the service: two sources would drift.
 *
 * <p>Not part of the visibility lag's proof: the store transaction sets its own limits inside the transaction.
 * Flyway migrates on its own unpooled connection (application.yaml {@code spring.flyway.*}) and is not bound by it.
 */
public class StatementTimeoutBackstop implements BeanPostProcessor {

    private static final String INIT_SQL_PROPERTY = "spring.datasource.hikari.connection-init-sql";

    private final Environment environment;

    /**
     * Creates the backstop.
     *
     * @param environment the environment the intake settings are bound from
     */
    public StatementTimeoutBackstop(final Environment environment) {
        this.environment = environment;
    }

    // The pool is a bean the container owns and closes; this only sets its init SQL before it starts.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public Object postProcessAfterInitialization(final Object bean, final String beanName) {
        if (bean instanceof HikariDataSource pool) {
            final String existing = pool.getConnectionInitSql();
            if (existing != null && !existing.isBlank()) {
                throw new IllegalStateException(INIT_SQL_PROPERTY + " must not be set: the pool's statement_timeout "
                        + "is set from resultsstore.intake.store.statement-timeout");
            }
            pool.setConnectionInitSql(initSql(statementTimeout()));
        }
        return bean;
    }

    /**
     * The init SQL for a statement timeout.
     *
     * @param statementTimeout the intake statement timeout
     * @return {@code SET statement_timeout = '<milliseconds>ms'}
     */
    /* default */ static String initSql(final Duration statementTimeout) {
        return "SET statement_timeout = '" + statementTimeout.toMillis() + "ms'";
    }

    /** The bound intake statement timeout, its default included, as {@link IntakeProperties} holds it. */
    private Duration statementTimeout() {
        return Binder.get(environment).bindOrCreate("resultsstore.intake", IntakeProperties.class).store()
                .statementTimeout();
    }
}
