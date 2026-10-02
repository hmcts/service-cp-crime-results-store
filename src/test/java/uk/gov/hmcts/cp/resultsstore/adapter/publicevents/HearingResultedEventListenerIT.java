package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.TestSocketUtils;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.application.ShareStore;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Stored;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

/**
 * The shared durable subscription against a real (embedded) Artemis broker, with the committed
 * topic, subscription name and selector, delivering to intake: each message selected reaches its
 * receipt on Testcontainers Postgres.
 *
 * <p>The share store and the observer are stand-ins until their adapters land (tasks.md wiring note:
 * {@code ShareStore} in T008, {@code IntakeObserver} in T013), so a share's receipt stays
 * {@code RECEIVED} here.
 */
@SpringBootTest(properties = "resultsstore.publicevents.enabled=true")
@ActiveProfiles("test")
class HearingResultedEventListenerIT {

    private static final String TOPIC = "public.event";

    private static final String SUBSCRIPTION = "resultsstore-service.sdg";

    private static final String HEARING_RESULTED = "public.events.hearing.hearing-resulted";

    private static final Duration WITHIN = Duration.ofSeconds(30);

    private static final String BROKER_URL = "tcp://localhost:" + TestSocketUtils.findAvailableTcpPort();

    /** Started once for the JVM: the Spring context outlives this class and closes its listener later. */
    private static EmbeddedActiveMQ broker;

    @MockitoBean
    private ShareStore shareStore;

    @MockitoBean
    private IntakeObserver observer;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void pointAtTheEmbeddedBrokerAndTheStore(final DynamicPropertyRegistry registry) throws Exception {
        startTheBroker();
        registry.add("spring.artemis.broker-url", () -> BROKER_URL);
        PostgresTestSupport.register(registry);
    }

    @BeforeEach
    void awaitTheSubscription() {
        when(shareStore.store(any())).thenAnswer(invocation -> new Stored(UUID.randomUUID(), Instant.now(), false,
                false));
        await().atMost(WITHIN).until(() -> !subscriptionsOnTheTopic().isEmpty());
    }

    @Test
    void subscription_should_be_shared_durable_and_named() throws Exception {
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

        await().atMost(WITHIN).until(() -> receipts(hearingId) == 1);
        assertThat(jdbc.sql("SELECT status FROM event_receipt WHERE hearing_id = :hearingId")
                .param("hearingId", UUID.fromString(hearingId)).query(String.class).single()).isEqualTo("RECEIVED");
        verify(shareStore, timeout(WITHIN.toMillis())).store(argThat(request ->
                request.identity().rawHearingId().equals(hearingId)
                        && "2026-09-30".equals(request.identity().rawHearingDay())
                        && "2026-09-30T15:04:05.000Z".equals(request.identity().rawSharedTime())));
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
        try (ActiveMQConnectionFactory publisher = new ActiveMQConnectionFactory(BROKER_URL);
             JMSContext session = publisher.createContext()) {
            final Topic topic = session.createTopic(TOPIC);
            final TextMessage message = session.createTextMessage(body);
            // The broker filters on this property; it cannot read the body.
            message.setStringProperty("CPPNAME", eventName);
            session.createProducer().send(topic, message);
        } catch (final JMSException problem) {
            throw new IllegalStateException("could not publish " + eventName, problem);
        }
    }

    private static List<Queue> subscriptionsOnTheTopic() throws Exception {
        return broker.getActiveMQServer().getPostOffice().listQueuesForAddress(SimpleString.of(TOPIC));
    }

    private static synchronized void startTheBroker() throws Exception {
        if (broker != null) {
            return;
        }
        final ConfigurationImpl configuration = new ConfigurationImpl();
        configuration.setName("public-event-test-broker")
                .setPersistenceEnabled(false)
                .setSecurityEnabled(false)
                .setJMXManagementEnabled(false);
        configuration.addAcceptorConfiguration("tcp", BROKER_URL);
        broker = new EmbeddedActiveMQ().setConfiguration(configuration);
        broker.start();
    }
}
