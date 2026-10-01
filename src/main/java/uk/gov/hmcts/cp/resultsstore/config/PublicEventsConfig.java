package uk.gov.hmcts.cp.resultsstore.config;

import jakarta.jms.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.jms.autoconfigure.JmsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;

/**
 * The listener container for the shared durable subscription to {@code public.event}.
 *
 * <p>Topic domain and durability come from {@code spring.jms.*}; the destination, subscription name
 * and selector are on the listener, read from {@code resultsstore.publicevents.*}. Shared has no
 * Spring property, so it is set here. No client id is set: a shared durable subscription is keyed by
 * its name alone, and a client id every replica carried would make the broker refuse the second pod.
 *
 * <p>The connection factory is looked up by bean name, not type. {@code cp-audit-filter-springboot}
 * contributes a {@code @Primary} {@code auditConnectionFactory} pointed at the audit broker, so a
 * by-type injection would silently subscribe on the wrong broker. It is then unwrapped so the
 * container owns its connection rather than sharing Boot's caching one.
 */
@Configuration(proxyBeanMethods = false)
public class PublicEventsConfig {

    /** Referenced by the listener annotation. */
    public static final String LISTENER_CONTAINER_FACTORY = "publicEventListenerContainerFactory";

    /** The name Boot's Artemis auto-configuration registers its connection factory under. */
    public static final String BOOT_CONNECTION_FACTORY = "jmsConnectionFactory";

    private static final Logger LOG = LoggerFactory.getLogger(PublicEventsConfig.class);

    /** One consumer per pod: the deployment scales on replicas. */
    private static final String ONE_PER_POD = "1";

    @Bean(LISTENER_CONTAINER_FACTORY)
    public DefaultJmsListenerContainerFactory publicEventListenerContainerFactory(
            @Qualifier(BOOT_CONNECTION_FACTORY) final ConnectionFactory connectionFactory,
            final JmsProperties jms,
            @Value("${resultsstore.publicevents.enabled}") final boolean enabled) {

        final DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(ConnectionFactoryUnwrapper.unwrap(connectionFactory));
        factory.setPubSubDomain(jms.isPubSubDomain());
        factory.setSubscriptionDurable(jms.isSubscriptionDurable());
        factory.setSubscriptionShared(true);
        factory.setConcurrency(ONE_PER_POD);
        // Transacted, so a listener failure rolls the message back to the broker instead of it
        // being acknowledged before the listener ran (the AUTO_ACKNOWLEDGE default).
        factory.setSessionTransacted(true);
        factory.setErrorHandler(PublicEventsConfig::notApplied);
        factory.setAutoStartup(enabled);
        return factory;
    }

    /** Logs at ERROR; without a handler the container logs listener failures at WARN. */
    private static void notApplied(final Throwable failure) {
        LOG.error("A public event was not applied; the session is rolled back and the broker will "
                + "redeliver it. cause={}", failure.getClass().getName(), failure);
    }
}
