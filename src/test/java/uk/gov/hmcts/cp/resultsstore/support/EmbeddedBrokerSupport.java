package uk.gov.hmcts.cp.resultsstore.support;

import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSProducer;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.util.List;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.test.util.TestSocketUtils;

/**
 * An embedded Artemis broker for the suites that run the subscription (research R19): the estate's
 * {@code public.event} topic, publishing with the {@code CPPNAME} property the selector reads, and a
 * look at the subscription queue and the dead-letter address.
 *
 * <p>Each suite starts its own broker on its own port and keeps it for the JVM: the Spring context
 * outlives the suite and closes its listener later. Messages on {@code public.event} go to the
 * dead-letter address {@value #DEAD_LETTERS} after {@code maxDeliveryAttempts} deliveries.
 */
public final class EmbeddedBrokerSupport {

    /** The estate's shared public-event topic. */
    public static final String TOPIC = "public.event";

    /** The shared durable subscription's name. */
    public static final String SUBSCRIPTION = "resultsstore-service.sdg";

    /** The dead-letter address, with one anycast queue of the same name. */
    public static final String DEAD_LETTERS = "DLA";

    private final String brokerUrl;

    private final EmbeddedActiveMQ broker;

    private EmbeddedBrokerSupport(final String url, final EmbeddedActiveMQ broker) {
        this.brokerUrl = url;
        this.broker = broker;
    }

    /**
     * Starts a broker on a free port.
     *
     * @param name                the broker's name
     * @param maxDeliveryAttempts deliveries of one message before it is dead-lettered
     * @return the running broker
     * @throws Exception when it cannot start
     */
    public static EmbeddedBrokerSupport start(final String name, final int maxDeliveryAttempts) throws Exception {
        final String url = "tcp://localhost:" + TestSocketUtils.findAvailableTcpPort();
        final ConfigurationImpl configuration = new ConfigurationImpl();
        configuration.setName(name)
                .setPersistenceEnabled(false)
                .setSecurityEnabled(false)
                .setJMXManagementEnabled(false);
        configuration.addAcceptorConfiguration("tcp", url);
        configuration.addAddressSetting(TOPIC, new AddressSettings()
                .setDeadLetterAddress(SimpleString.of(DEAD_LETTERS))
                .setMaxDeliveryAttempts(maxDeliveryAttempts));
        configuration.addQueueConfiguration(QueueConfiguration.of(DEAD_LETTERS)
                .setAddress(DEAD_LETTERS)
                .setRoutingType(RoutingType.ANYCAST));
        final EmbeddedActiveMQ broker = new EmbeddedActiveMQ().setConfiguration(configuration);
        broker.start();
        return new EmbeddedBrokerSupport(url, broker);
    }

    /** The broker's URL. */
    public String url() {
        return brokerUrl;
    }

    /**
     * Publishes a text message to {@code public.event}.
     *
     * @param eventName the {@code CPPNAME} property the broker's selector filters on
     * @param body      the text
     */
    public void publish(final String eventName, final String body) {
        publish(eventName, body, false);
    }

    /**
     * Publishes a text message to {@code public.event}, optionally with no message id.
     *
     * @param eventName        the {@code CPPNAME} property
     * @param body             the text
     * @param disableMessageId whether the producer asks for no {@code JMSMessageID} (research R3)
     */
    public void publish(final String eventName, final String body, final boolean disableMessageId) {
        try (ActiveMQConnectionFactory publisher = new ActiveMQConnectionFactory(brokerUrl);
             JMSContext session = publisher.createContext()) {
            final Topic topic = session.createTopic(TOPIC);
            final TextMessage message = session.createTextMessage(body);
            message.setStringProperty("CPPNAME", eventName);
            final JMSProducer producer = session.createProducer();
            producer.setDisableMessageID(disableMessageId);
            producer.send(topic, message);
        } catch (final JMSException problem) {
            throw new IllegalStateException("could not publish " + eventName, problem);
        }
    }

    /** The subscription queues on {@code public.event}. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // listQueuesForAddress declares Exception.
    public List<Queue> subscriptions() {
        try {
            return broker.getActiveMQServer().getPostOffice().listQueuesForAddress(SimpleString.of(TOPIC));
        } catch (final Exception problem) {
            throw new IllegalStateException("could not list the subscriptions", problem);
        }
    }

    /** Messages waiting on the subscription or in delivery from it; 0 when there is none yet. */
    public long inFlight() {
        return subscriptions().stream()
                .mapToLong(queue -> queue.getMessageCount() + queue.getDeliveringCount())
                .sum();
    }

    /** Consumers on the subscription. */
    public int consumers() {
        return subscriptions().stream().mapToInt(Queue::getConsumerCount).sum();
    }

    /** Messages on the dead-letter address. */
    public long deadLetters() {
        return broker.getActiveMQServer().locateQueue(SimpleString.of(DEAD_LETTERS)).getMessageCount();
    }
}
