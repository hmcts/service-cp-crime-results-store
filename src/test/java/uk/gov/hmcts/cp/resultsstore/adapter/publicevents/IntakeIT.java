package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.support.JmsUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.resultsstore.application.Arrival;
import uk.gov.hmcts.cp.resultsstore.config.PublicEventsConfig;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.support.EmbeddedBrokerSupport;
import uk.gov.hmcts.cp.resultsstore.support.FailingFirstCommitConnectionFactory;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;

/**
 * Intake end to end (US1 to US4, US6; SC-001 to SC-005, SC-007): messages published to an embedded
 * Artemis broker reach the real listener, intake and store on Testcontainers Postgres, with no stand-in.
 *
 * <p>The broker dead-letters a message after 3 deliveries; the store gives up waiting for the day lock
 * after 2 s; the redelivery pause is off ({@code test} profile). The client takes no message ahead of
 * the one it is working on ({@code consumerWindowSize=0}), so a second consumer gets the next message
 * while the first is busy. Waits are on observable rows and queue counts, never on time alone.
 */
@SpringBootTest(properties = {"resultsstore.publicevents.enabled=true", "resultsstore.intake.store.lock-timeout=2s"})
@ActiveProfiles("test")
@DisplayName("intake end to end")
class IntakeIT {

    private static final Duration WITHIN = Duration.ofSeconds(30);

    /** How long a settled receipt must keep its attempts to show no redelivery followed. */
    private static final Duration SETTLE = Duration.ofSeconds(2);

    private static final String DAY = "2026-10-02";

    private static final String SHARED_TIME = "2026-10-02T14:19:50.706Z";

    private static final int MAX_DELIVERY_ATTEMPTS = 3;

    /** Started once for the JVM: the Spring context outlives this class and closes its listener later. */
    private static EmbeddedBrokerSupport broker;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HearingResultedEventListener listener;

    @Autowired
    private JmsListenerEndpointRegistry registry;

    @Autowired
    @Qualifier(PublicEventsConfig.LISTENER_CONTAINER_FACTORY)
    private DefaultJmsListenerContainerFactory containerFactory;

    @Autowired
    @Qualifier(PublicEventsConfig.BOOT_CONNECTION_FACTORY)
    private ConnectionFactory bootConnectionFactory;

    @Value("${resultsstore.publicevents.selector}")
    private String selector;

    private final List<DefaultMessageListenerContainer> secondPods = new ArrayList<>();

    private UUID hearingId;

    @DynamicPropertySource
    static void brokerAndStore(final DynamicPropertyRegistry registry) throws Exception {
        startTheBroker();
        registry.add("spring.artemis.broker-url", () -> broker.url() + "?consumerWindowSize=0");
        PostgresTestSupport.register(registry);
    }

    @BeforeEach
    void emptyTablesAndAwaitTheSubscription() {
        jdbc.sql("TRUNCATE event_receipt, share_defendant, hearing_share_payload, hearing_share, hearing_day_head")
                .update();
        hearingId = UUID.randomUUID();
        await().atMost(WITHIN).until(() -> broker.consumers() >= 1 && broker.inFlight() == 0);
    }

    @AfterEach
    void stopTheSecondPodsAndRestartTheListener() {
        secondPods.forEach(DefaultMessageListenerContainer::shutdown);
        secondPods.clear();
        registry.start();
    }

    @Test
    void share_should_be_stored_and_acknowledged() {
        final String text = SampleShares.share(hearingId, DAY, SHARED_TIME);

        broker.publish(SampleShares.HEARING_RESULTED, text);

        await().atMost(WITHIN).until(() -> "STORED".equals(receiptOf(hearingId).get("status")));
        await().atMost(WITHIN).until(() -> broker.inFlight() == 0);
        assertNoRedelivery(hearingId);
        final UUID shareId = SampleShares.read(text).identity().shareId();
        assertThat(receiptOf(hearingId)).containsEntry("share_id", shareId).containsEntry("attempts", 1);
        assertThat(jdbc.sql("SELECT is_latest FROM hearing_share WHERE share_id = :shareId")
                .param("shareId", shareId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT payload_text FROM hearing_share_payload WHERE share_id = :shareId")
                .param("shareId", shareId).query(String.class).single()).isEqualTo(text);
        assertEveryReceiptSettled();
    }

    @Test
    void store_that_fails_once_should_store_the_share_on_the_redelivery() throws SQLException {
        createTheDay();

        try (Connection holder = dataSource.getConnection()) {
            holdTheDay(holder);
            broker.publish(SampleShares.HEARING_RESULTED, SampleShares.share(hearingId, DAY, SHARED_TIME));
            // The first attempt gave up at the lock timeout and rolled back; the second is under way.
            await().atMost(WITHIN).until(() -> attempts(hearingId) == 2);
            holder.rollback();
        }

        await().atMost(WITHIN).until(() -> "STORED".equals(receiptOf(hearingId).get("status")));
        await().atMost(WITHIN).until(() -> broker.inFlight() == 0);
        assertThat(receiptOf(hearingId)).containsEntry("attempts", 2);
        assertThat(receipts(hearingId)).isEqualTo(1);
        assertThat(shares(hearingId)).isEqualTo(1);
        assertEveryReceiptSettled();
    }

    @Test
    void acknowledgement_lost_after_the_store_committed_should_leave_one_share_and_settle_on_redelivery() {
        registry.stop();
        final FailingFirstCommitConnectionFactory failing =
                new FailingFirstCommitConnectionFactory(ConnectionFactoryUnwrapper.unwrap(bootConnectionFactory));
        startASecondPod(failing);

        broker.publish(SampleShares.HEARING_RESULTED, SampleShares.share(hearingId, DAY, SHARED_TIME));

        await().atMost(WITHIN).until(() -> attempts(hearingId) == 2 && broker.inFlight() == 0);
        assertThat(failing.hasFailed()).isTrue();
        assertThat(receiptOf(hearingId)).containsEntry("status", "STORED");
        assertThat(receipts(hearingId)).isEqualTo(1);
        assertThat(shares(hearingId)).isEqualTo(1);
        assertNoRedelivery(hearingId);
        assertEveryReceiptSettled();
    }

    @Test
    void unreadable_message_should_be_recorded_acknowledged_and_not_redelivered() {
        final String text = "not json " + UUID.randomUUID();

        broker.publish(SampleShares.HEARING_RESULTED, text);

        await().atMost(WITHIN).until(() -> receiptsWithText(text) == 1);
        await().atMost(WITHIN).until(() -> broker.inFlight() == 0);
        final Map<String, Object> receipt = jdbc.sql("SELECT * FROM event_receipt WHERE message_text = :text")
                .param("text", text).query().singleRow();
        assertThat(receipt).containsEntry("status", "UNREADABLE").containsEntry("reason", "NOT_JSON");
        await().during(SETTLE).atMost(SETTLE.plus(WITHIN)).until(() -> jdbc
                .sql("SELECT attempts FROM event_receipt WHERE message_text = :text")
                .param("text", text).query(Integer.class).single() == 1);
        assertEveryReceiptSettled();
    }

    @Test
    void message_with_no_identity_should_be_recorded_acknowledged_and_not_redelivered() {
        final String text = """
                {"hearing":{"id":"%s"},"hearingDay":"%s"}
                """.formatted(hearingId, DAY);

        broker.publish(SampleShares.HEARING_RESULTED, text);

        await().atMost(WITHIN).until(() -> !receiptOf(hearingId).isEmpty());
        await().atMost(WITHIN).until(() -> broker.inFlight() == 0);
        assertThat(receiptOf(hearingId))
                .containsEntry("status", "NO_IDENTITY")
                .containsEntry("reason", "MISSING_SHARED_TIME")
                .containsEntry("message_text", text)
                .containsEntry("hearing_day", Date.valueOf(DAY));
        assertNoRedelivery(hearingId);
        assertEveryReceiptSettled();
    }

    @Test
    void share_that_fails_on_every_delivery_should_end_on_the_dead_letter_address_with_its_attempts()
            throws SQLException {
        createTheDay();
        final long deadBefore = broker.deadLetters();

        try (Connection holder = dataSource.getConnection()) {
            holdTheDay(holder);
            broker.publish(SampleShares.HEARING_RESULTED, SampleShares.share(hearingId, DAY, SHARED_TIME));
            await().atMost(WITHIN).until(() -> broker.deadLetters() == deadBefore + 1);
            holder.rollback();
        }

        assertThat(receiptOf(hearingId))
                .containsEntry("status", "RECEIVED")
                .containsEntry("attempts", MAX_DELIVERY_ATTEMPTS);
        assertThat(shares(hearingId)).isZero();
        assertThat(broker.inFlight()).isZero();
    }

    @Test
    void fifty_out_of_order_shares_on_two_consumers_should_give_one_latest_and_a_gapless_chain() {
        startASecondPod(ConnectionFactoryUnwrapper.unwrap(bootConnectionFactory));
        final Instant first = Instant.parse("2026-10-02T09:00:00.000Z");
        final List<Instant> sharedTimes = new ArrayList<>();
        for (int share = 0; share < 50; share++) {
            sharedTimes.add(first.plus(Duration.ofMinutes(share)));
        }
        final List<Instant> published = new ArrayList<>(sharedTimes);
        Collections.shuffle(published, new Random(42));

        published.forEach(sharedTime -> broker.publish(SampleShares.HEARING_RESULTED,
                SampleShares.share(hearingId, DAY, sharedTime.toString())));

        await().atMost(Duration.ofSeconds(60)).until(() -> stored(hearingId) == 50);
        await().atMost(WITHIN).until(() -> broker.inFlight() == 0);
        final List<Map<String, Object>> chain = jdbc.sql("""
                SELECT share_id, shared_at, is_latest, predecessor_share_id FROM hearing_share
                 WHERE hearing_id = :hearingId ORDER BY shared_at
                """).param("hearingId", hearingId).query().listOfRows();
        assertThat(chain).hasSize(50);
        for (int index = 0; index < chain.size(); index++) {
            final Object expectedPredecessor = index == 0 ? null : chain.get(index - 1).get("share_id");
            assertThat(chain.get(index))
                    .containsEntry("predecessor_share_id", expectedPredecessor)
                    .containsEntry("is_latest", index == chain.size() - 1);
        }
        assertThat(dayRow())
                .containsEntry("share_count", 50)
                .containsEntry("latest_share_id", chain.getLast().get("share_id"));
        assertEveryReceiptSettled();
    }

    @Test
    void same_share_twice_at_once_should_give_one_stored_and_one_duplicate() throws SQLException {
        startASecondPod(ConnectionFactoryUnwrapper.unwrap(bootConnectionFactory));
        createTheDay();
        final String text = SampleShares.share(hearingId, DAY, SHARED_TIME);

        try (Connection holder = dataSource.getConnection()) {
            holdTheDay(holder);
            broker.publish(SampleShares.HEARING_RESULTED, text);
            broker.publish(SampleShares.HEARING_RESULTED, text);
            // Both receipts written: each consumer holds one message and waits for the day.
            await().atMost(WITHIN).until(() -> receipts(hearingId) == 2);
            holder.rollback();
        }

        await().atMost(WITHIN).until(() -> settled(hearingId) == 2 && broker.inFlight() == 0);
        final List<Map<String, Object>> receipts = jdbc.sql(
                "SELECT status, share_id FROM event_receipt WHERE hearing_id = :hearingId ORDER BY status")
                .param("hearingId", hearingId).query().listOfRows();
        final UUID shareId = SampleShares.read(text).identity().shareId();
        assertThat(receipts).containsExactly(
                Map.of("status", "DUPLICATE", "share_id", shareId),
                Map.of("status", "STORED", "share_id", shareId));
        assertThat(shares(hearingId)).isEqualTo(1);
        assertEveryReceiptSettled();
    }

    @Test
    void message_with_no_message_id_should_be_stored_under_its_checksum_key() {
        final String text = SampleShares.share(hearingId, DAY, SHARED_TIME);

        broker.publish(SampleShares.HEARING_RESULTED, text, true);

        await().atMost(WITHIN).until(() -> "STORED".equals(receiptOf(hearingId).get("status")));
        assertThat(receiptOf(hearingId))
                .containsEntry("message_id", Arrival.SYNTHETIC_KEY_PREFIX + PayloadChecksum.sha256Hex(text));
        await().atMost(WITHIN).until(() -> broker.inFlight() == 0);
        assertEveryReceiptSettled();
    }

    /** A second listener container on the one shared subscription, built from the same factory. */
    private void startASecondPod(final ConnectionFactory connectionFactory) {
        final SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId("second-pod-" + secondPods.size());
        endpoint.setDestination(EmbeddedBrokerSupport.TOPIC);
        endpoint.setSubscription(EmbeddedBrokerSupport.SUBSCRIPTION);
        endpoint.setSelector(selector);
        endpoint.setMessageListener(message -> {
            try {
                listener.onHearingResulted(message);
            } catch (final JMSException unreadable) {
                // As the annotated listener does: the container rolls the session back.
                throw JmsUtils.convertJmsAccessException(unreadable);
            }
        });
        final DefaultMessageListenerContainer container = containerFactory.createListenerContainer(endpoint);
        container.setConnectionFactory(connectionFactory);
        container.afterPropertiesSet();
        container.start();
        secondPods.add(container);
        await().atMost(WITHIN).until(() -> container.getActiveConsumerCount() == 1);
    }

    private void createTheDay() {
        jdbc.sql("INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (:hearingId, :day)")
                .param("hearingId", hearingId).param("day", LocalDate.parse(DAY)).update();
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

    private void assertNoRedelivery(final UUID hearing) {
        final int attempts = attempts(hearing);
        await().during(SETTLE).atMost(SETTLE.plus(WITHIN)).until(() -> attempts(hearing) == attempts);
    }

    private void assertEveryReceiptSettled() {
        assertThat(jdbc.sql("SELECT count(*) FROM event_receipt WHERE status = 'RECEIVED'")
                .query(Integer.class).single()).as("receipts left RECEIVED").isZero();
    }

    private Map<String, Object> receiptOf(final UUID hearing) {
        return jdbc.sql("SELECT * FROM event_receipt WHERE hearing_id = :hearingId")
                .param("hearingId", hearing).query().listOfRows().stream().findFirst().orElse(Map.of());
    }

    private Map<String, Object> dayRow() {
        return jdbc.sql("SELECT * FROM hearing_day_head WHERE hearing_id = :hearingId AND hearing_day = :day")
                .param("hearingId", hearingId).param("day", LocalDate.parse(DAY)).query().singleRow();
    }

    private int attempts(final UUID hearing) {
        return jdbc.sql("SELECT COALESCE(max(attempts), 0) FROM event_receipt WHERE hearing_id = :hearingId")
                .param("hearingId", hearing).query(Integer.class).single();
    }

    private int receipts(final UUID hearing) {
        return count("SELECT count(*) FROM event_receipt WHERE hearing_id = :hearingId", hearing);
    }

    private int settled(final UUID hearing) {
        return count("SELECT count(*) FROM event_receipt WHERE hearing_id = :hearingId AND status <> 'RECEIVED'",
                hearing);
    }

    private int stored(final UUID hearing) {
        return count("SELECT count(*) FROM event_receipt WHERE hearing_id = :hearingId AND status = 'STORED'",
                hearing);
    }

    private int shares(final UUID hearing) {
        return count("SELECT count(*) FROM hearing_share WHERE hearing_id = :hearingId", hearing);
    }

    private int receiptsWithText(final String text) {
        return jdbc.sql("SELECT count(*) FROM event_receipt WHERE message_text = :text")
                .param("text", text).query(Integer.class).single();
    }

    private int count(final String sql, final UUID hearing) {
        return jdbc.sql(sql).param("hearingId", hearing).query(Integer.class).single();
    }

    private static synchronized void startTheBroker() throws Exception {
        if (broker == null) {
            broker = EmbeddedBrokerSupport.start("intake-test-broker", MAX_DELIVERY_ATTEMPTS);
        }
    }
}
