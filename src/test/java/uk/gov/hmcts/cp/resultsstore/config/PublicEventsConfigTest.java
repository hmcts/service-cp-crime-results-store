package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.jms.ConnectionFactory;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.jms.autoconfigure.JmsProperties;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.adapter.ListenerExecutionFailedException;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;

/** The container behind the shared durable subscription (FR-001, FR-006; contracts/inbound-event.md). */
@DisplayName("public events listener container")
class PublicEventsConfigTest {

    /** Stands for message text a database error can quote back, such as a failing row's detail. */
    private static final String PAYLOAD_MARKER = "PAYLOAD-MARKER-Smith";

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

    @Test
    void error_handler_should_log_the_cause_chain_by_class_name_only_never_its_text() {
        final SQLException driver = new SQLException(
                "ERROR: new row violates check constraint. Detail: Failing row contains (" + PAYLOAD_MARKER + ")",
                "23514");
        final Throwable failure = new ListenerExecutionFailedException("Listener method threw " + PAYLOAD_MARKER,
                new RetryableIntakeException(IntakeStage.RECEIPT, IntakeFailureCause.DATABASE,
                        new DataIntegrityViolationException("could not execute " + PAYLOAD_MARKER, driver)));

        try (CapturedLog log = CapturedLog.forClass(PublicEventsConfig.class)) {
            container(true).getErrorHandler().handleError(failure);

            assertThat(log.events()).hasSize(1);
            final ILoggingEvent event = log.events().getFirst();
            assertThat(event.getThrowableProxy()).as("no throwable, so no stack trace or cause messages").isNull();
            assertThat(event.getFormattedMessage())
                    .doesNotContain(PAYLOAD_MARKER)
                    .contains(ListenerExecutionFailedException.class.getName(),
                            RetryableIntakeException.class.getName(),
                            DataIntegrityViolationException.class.getName(),
                            SQLException.class.getName());
        }
    }

    @Test
    void cause_classes_should_end_a_cause_that_points_at_itself_at_the_depth_limit() {
        final IllegalStateException loop = new IllegalStateException() {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(PublicEventsConfig.causeClasses(loop).split(" <- "))
                .hasSize(16)
                .containsOnly(loop.getClass().getName());
    }

    @Test
    void cause_classes_should_name_at_most_sixteen_links_of_a_long_chain() {
        Throwable chain = new IllegalStateException();
        for (int link = 0; link < 40; link++) {
            chain = new IllegalArgumentException(chain);
        }

        assertThat(PublicEventsConfig.causeClasses(chain).split(" <- ")).hasSize(16);
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
