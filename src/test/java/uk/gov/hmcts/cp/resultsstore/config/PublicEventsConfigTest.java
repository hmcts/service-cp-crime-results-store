package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jms.autoconfigure.JmsProperties;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

/** The container behind the shared durable subscription (FR-001, FR-006; contracts/inbound-event.md). */
class PublicEventsConfigTest {

    private final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

    @Test
    void container_should_be_a_transacted_shared_durable_topic_consumer_one_per_pod() {
        final DefaultMessageListenerContainer container = container(true);

        assertThat(container.isSessionTransacted()).as("transacted: commit is the acknowledgement").isTrue();
        assertThat(ReflectionTestUtils.getField(container, "transactionManager"))
                .as("no JMS transaction manager").isNull();
        assertThat(container.isPubSubDomain()).isTrue();
        assertThat(container.isSubscriptionDurable()).isTrue();
        assertThat(container.isSubscriptionShared()).isTrue();
        assertThat(container.getClientId()).as("no client id on a shared subscription").isNull();
        assertThat(container.getConcurrentConsumers()).isEqualTo(1);
        assertThat(container.getMaxConcurrentConsumers()).isEqualTo(1);
        assertThat(container.getConnectionFactory()).isSameAs(connectionFactory);
        assertThat(container.isAutoStartup()).isTrue();
    }

    @Test
    void container_when_disabled_should_not_start_on_its_own() {
        assertThat(container(false).isAutoStartup()).isFalse();
    }

    private DefaultMessageListenerContainer container(final boolean enabled) {
        final JmsProperties jms = new JmsProperties();
        jms.setPubSubDomain(true);
        jms.setSubscriptionDurable(true);
        final SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId("test");
        endpoint.setDestination("public.event");
        endpoint.setSubscription("resultsstore-service.sdg");
        endpoint.setMessageListener(message -> {
            // Never called: the container is built, not started.
        });
        return new PublicEventsConfig()
                .publicEventListenerContainerFactory(connectionFactory, jms, enabled)
                .createListenerContainer(endpoint);
    }
}
