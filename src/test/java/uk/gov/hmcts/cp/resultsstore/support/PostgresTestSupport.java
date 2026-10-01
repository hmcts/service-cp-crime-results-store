package uk.gov.hmcts.cp.resultsstore.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared Postgres for the suites that need the store.
 *
 * <p>One container per JVM, started on first use and left to the Ryuk reaper at exit, so the
 * suites pay the start-up cost once between them.
 */
public final class PostgresTestSupport {

    private static final String IMAGE = "postgres:16";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("resultsstore")
            .withUsername("resultsstore")
            .withPassword("resultsstore");

    private PostgresTestSupport() {
        // Static fixture holder.
    }

    /** Returns the shared container, starting it if this is the first call. */
    public static PostgreSQLContainer container() {
        synchronized (POSTGRES) {
            if (!POSTGRES.isRunning()) {
                POSTGRES.start();
            }
        }
        return POSTGRES;
    }

    /**
     * Points the datasource at the shared container and re-enables the datasource and Flyway
     * auto-configuration the {@code test} profile excludes.
     *
     * @param registry the suite's property registry
     */
    public static void register(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> container().getUsername());
        registry.add("spring.datasource.password", () -> container().getPassword());
        registry.add("spring.autoconfigure.exclude", () -> "");
        registry.add("management.endpoint.health.group.readiness.include", () -> "readinessState,db");
    }
}
