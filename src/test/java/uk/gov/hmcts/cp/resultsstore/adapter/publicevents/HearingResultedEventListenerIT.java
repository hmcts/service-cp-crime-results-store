package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.activemq.artemis.core.server.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.domain.ShareId;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcReceiptStore;
import uk.gov.hmcts.cp.resultsstore.persistence.JdbcShareStore;
import uk.gov.hmcts.cp.resultsstore.support.EmbeddedBrokerSupport;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

/**
 * The shared durable subscription against a real (embedded) Artemis broker, with the committed
 * topic, subscription name and selector, delivering to intake: each message selected reaches its
 * receipt on Testcontainers Postgres.
 *
 * <p>The observer is a stand-in until its adapter lands (tasks.md wiring note: {@code IntakeObserver}
 * in T013); the share store is the real one.
 */
@SpringBootTest(properties = {"resultsstore.publicevents.enabled=true", "resultsstore.intake.receipt-timeout=7s",
    "resultsstore.intake.store.transaction-timeout=50s"})
@ActiveProfiles("test")
class HearingResultedEventListenerIT {

    private static final String SUBSCRIPTION = EmbeddedBrokerSupport.SUBSCRIPTION;

    private static final String HEARING_RESULTED = "public.events.hearing.hearing-resulted";

    private static final Duration WITHIN = Duration.ofSeconds(30);

    /** How long a settled receipt must stay at one attempt to show no redelivery followed. */
    private static final Duration SETTLE = Duration.ofSeconds(2);

    /** Started once for the JVM: the Spring context outlives this class and closes its listener later. */
    private static EmbeddedBrokerSupport broker;

    @MockitoBean
    private IntakeObserver observer;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcReceiptStore receiptStore;

    @Autowired
    private JdbcShareStore shareStore;

    @DynamicPropertySource
    static void pointAtTheEmbeddedBrokerAndTheStore(final DynamicPropertyRegistry registry) throws Exception {
        startTheBroker();
        registry.add("spring.artemis.broker-url", broker::url);
        PostgresTestSupport.register(registry);
    }

    @BeforeEach
    void awaitTheSubscription() {
        await().atMost(WITHIN).until(() -> !subscriptionsOnTheTopic().isEmpty());
    }

    @Test
    void subscription_should_be_shared_durable_and_named() {
        assertThat(subscriptionsOnTheTopic())
                .singleElement()
                .satisfies(queue -> {
                    // Artemis names a shared durable subscription without a client id after the
                    // subscription alone, escaping the dots.
                    assertThat(queue.getName().toString()).isEqualTo(SUBSCRIPTION.replace(".", "\\."));
                    assertThat(queue.isDurable()).isTrue();
                });
    }

    @Test
    void hearing_resulted_event_should_reach_its_receipt_and_the_store() {
        final String hearingId = UUID.randomUUID().toString();

        publish(HEARING_RESULTED, envelope(hearingId));

        await().atMost(WITHIN).until(() -> "STORED".equals(status(hearingId)));
        final UUID expectedShareId = ShareId.from(hearingId, "2026-09-30", "2026-09-30T15:04:05.000Z");
        assertThat(jdbc.sql("SELECT share_id FROM event_receipt WHERE hearing_id = :hearingId")
                .param("hearingId", UUID.fromString(hearingId)).query(UUID.class).single())
                .isEqualTo(expectedShareId);
        assertThat(jdbc.sql("SELECT count(*) FROM hearing_share WHERE share_id = :shareId")
                .param("shareId", expectedShareId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void delivery_that_intake_finishes_should_be_acknowledged_once_and_not_redelivered() {
        final String hearingId = UUID.randomUUID().toString();

        publish(HEARING_RESULTED, envelope(hearingId));

        await().atMost(WITHIN).until(() -> receipts(hearingId) == 1);
        final Queue subscription = subscriptionsOnTheTopic().getFirst();
        // The commit of the transacted session is the acknowledgement: nothing waits or is in delivery.
        await().atMost(WITHIN).until(() -> subscription.getMessageCount() == 0
                && subscription.getDeliveringCount() == 0);
        // A rolled-back delivery would come straight back (no pause in tests) and raise the attempts.
        await().during(SETTLE).atMost(SETTLE.plus(WITHIN)).until(() -> attempts(hearingId) == 1);
    }

    @Test
    void receipt_transaction_should_time_out_at_the_configured_receipt_timeout() {
        assertThat(ReflectionTestUtils.getField(receiptStore, "receiptTransaction"))
                .isInstanceOfSatisfying(TransactionTemplate.class,
                        template -> assertThat(template.getTimeout()).isEqualTo(7));
    }

    @Test
    void another_event_on_the_topic_should_not_be_delivered() {
        final String filtered = UUID.randomUUID().toString();
        final String delivered = UUID.randomUUID().toString();

        // Published first: once the second arrives, the first has had its chance.
        publish("public.progression.events.hearing-resulted", envelope(filtered));
        publish(HEARING_RESULTED, envelope(delivered));

        await().atMost(WITHIN).until(() -> receipts(delivered) == 1);
        assertThat(receipts(filtered)).isZero();
    }

    @Test
    void store_transaction_should_time_out_at_the_configured_transaction_timeout() {
        assertThat(ReflectionTestUtils.getField(shareStore, "storeTransaction"))
                .isInstanceOfSatisfying(TransactionTemplate.class,
                        template -> assertThat(template.getTimeout()).isEqualTo(50));
    }

    private String status(final String hearingId) {
        return jdbc.sql("SELECT status FROM event_receipt WHERE hearing_id = :hearingId")
                .param("hearingId", UUID.fromString(hearingId))
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private int attempts(final String hearingId) {
        return jdbc.sql("SELECT attempts FROM event_receipt WHERE hearing_id = :hearingId")
                .param("hearingId", UUID.fromString(hearingId))
                .query(Integer.class)
                .single();
    }

    private int receipts(final String hearingId) {
        return jdbc.sql("SELECT count(*) FROM event_receipt WHERE hearing_id = :hearingId")
                .param("hearingId", UUID.fromString(hearingId))
                .query(Integer.class)
                .single();
    }

    private static String envelope(final String hearingId) {
        return """
                {"_metadata": {"name": "public.events.hearing.hearing-resulted", "id": "%s"},
                 "hearing": {"id": "%s"},
                 "hearingDay": "2026-09-30",
                 "sharedTime": "2026-09-30T15:04:05.000Z",
                 "isReshare": false}
                """.formatted(UUID.randomUUID(), hearingId);
    }

    private static void publish(final String eventName, final String body) {
        broker.publish(eventName, body);
    }

    private static List<Queue> subscriptionsOnTheTopic() {
        return broker.subscriptions();
    }

    private static synchronized void startTheBroker() throws Exception {
        if (broker == null) {
            // Artemis's own default of 10 deliveries; this suite never fails a delivery on purpose.
            broker = EmbeddedBrokerSupport.start("public-event-test-broker", 10);
        }
    }
}
