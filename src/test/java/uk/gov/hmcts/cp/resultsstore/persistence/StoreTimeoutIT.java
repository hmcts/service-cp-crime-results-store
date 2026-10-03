package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;

/**
 * The store transaction's own timeouts (FR-020, SC-009): with the hearing-day row held by another
 * connection, the store gives up at its lock timeout, leaves nothing behind, and the settings end with
 * the transaction, so the next borrower of the same connection has the server's defaults.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("store timeouts")
class StoreTimeoutIT {

    private static final String DAY = "2026-10-02";

    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(1);

    /** The outer bound: long enough that only the lock timeout can end the wait first. */
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private static final List<String> TIMEOUT_SETTINGS =
            List.of("lock_timeout", "statement_timeout", "idle_in_transaction_session_timeout");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    /** One physical connection, so "the next transaction" is on the connection the store used. */
    private SingleConnectionDataSource storeConnection;

    private JdbcClient storeJdbc;

    private JdbcShareStore store;

    private JdbcReceiptStore receipts;

    private UUID hearingId;

    @DynamicPropertySource
    static void database(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @BeforeEach
    void emptyTablesAndOpenTheStoreConnection() {
        jdbc.sql("TRUNCATE event_receipt, share_defendant, hearing_share_payload, hearing_share, hearing_day_head")
                .update();
        storeConnection = new SingleConnectionDataSource(PostgresTestSupport.container().getJdbcUrl(),
                PostgresTestSupport.container().getUsername(), PostgresTestSupport.container().getPassword(), true);
        storeJdbc = JdbcClient.create(storeConnection);
        final DataSourceTransactionManager transactions = new DataSourceTransactionManager(storeConnection);
        receipts = new JdbcReceiptStore(storeJdbc, new TransactionTemplate(transactions));
        final TransactionTemplate storeTransaction = new TransactionTemplate(transactions);
        storeTransaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        store = new JdbcShareStore(storeJdbc, storeTransaction, receipts,
                new JdbcShareStore.Timeouts(LOCK_TIMEOUT, Duration.ofSeconds(3), Duration.ofSeconds(3)));
        hearingId = UUID.randomUUID();
    }

    @AfterEach
    void closeTheStoreConnection() {
        storeConnection.destroy();
    }

    @Test
    void store_should_give_up_at_the_lock_timeout_while_the_day_is_held_and_leave_nothing()
            throws SQLException {
        final StoreRequest request = received();
        jdbc.sql("INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (:hearingId, :day)")
                .param("hearingId", hearingId).param("day", LocalDate.parse(DAY)).update();

        try (Connection holder = dataSource.getConnection()) {
            holdTheDay(holder);
            final long started = System.nanoTime();

            assertThatThrownBy(() -> store.store(request))
                    .isInstanceOfSatisfying(RetryableIntakeException.class, failure -> {
                        assertThat(failure.getStage()).isEqualTo(IntakeStage.STORE);
                        assertThat(failure.getFailureCause()).isEqualTo(IntakeFailureCause.LOCK_TIMEOUT);
                    });

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(LOCK_TIMEOUT.plusSeconds(1));
            holder.rollback();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM hearing_share").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT share_count FROM hearing_day_head").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM event_receipt").query(String.class).single()).isEqualTo("RECEIVED");
    }

    @Test
    void timeouts_should_end_with_the_store_transaction() {
        final List<String> before = settings();

        store.store(received());

        assertThat(settings()).isEqualTo(before).doesNotContain("1s", "3s");
        final List<String> inTheNextTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(storeConnection)).execute(status -> settings());
        assertThat(inTheNextTransaction).isEqualTo(before);
    }

    @Test
    void timeouts_should_end_with_a_failed_store_transaction() throws SQLException {
        final List<String> before = settings();
        final StoreRequest request = received();
        jdbc.sql("INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (:hearingId, :day)")
                .param("hearingId", hearingId).param("day", LocalDate.parse(DAY)).update();
        try (Connection holder = dataSource.getConnection()) {
            holdTheDay(holder);
            assertThatThrownBy(() -> store.store(request)).isInstanceOf(RetryableIntakeException.class);
            holder.rollback();
        }

        assertThat(settings()).isEqualTo(before);
    }

    private StoreRequest received() {
        final String text = SampleShares.share(hearingId, DAY, "2026-10-02T14:19:50.706Z");
        receipts.recordArrival(SampleShares.arrival("ID:1", text));
        return SampleShares.request("ID:1", text);
    }

    private void holdTheDay(final Connection holder) throws SQLException {
        holder.setAutoCommit(false);
        try (PreparedStatement lock = holder.prepareStatement(
                "SELECT 1 FROM hearing_day_head WHERE hearing_id = ? AND hearing_day = ? FOR UPDATE")) {
            lock.setObject(1, hearingId);
            lock.setObject(2, LocalDate.parse(DAY));
            lock.executeQuery().close();
        }
    }

    private List<String> settings() {
        return TIMEOUT_SETTINGS.stream()
                .map(name -> storeJdbc.sql("SELECT current_setting(:name)").param("name", name)
                        .query(String.class).single())
                .toList();
    }
}
