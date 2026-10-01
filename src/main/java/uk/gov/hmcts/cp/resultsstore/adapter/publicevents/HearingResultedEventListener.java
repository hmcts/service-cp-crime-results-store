package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.resultsstore.config.PublicEventsConfig;

/**
 * Receives {@code public.events.hearing.hearing-resulted} from the shared durable subscription.
 *
 * <p>A stub for now: it logs the share's identity fields and acknowledges. Storing the share is the
 * first feature spec's job. Only identifiers are logged, never payload content.
 *
 * <p>A message it cannot read is logged at WARN and acknowledged rather than rolled back: a
 * malformed body will never parse, and redelivering it would only hold up the subscription.
 */
@Component
public class HearingResultedEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(HearingResultedEventListener.class);

    private final ObjectMapper mapper;

    public HearingResultedEventListener(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @JmsListener(
            destination = "${resultsstore.publicevents.topic}",
            subscription = "${resultsstore.publicevents.subscription}",
            selector = "${resultsstore.publicevents.selector}",
            containerFactory = PublicEventsConfig.LISTENER_CONTAINER_FACTORY)
    public void onHearingResulted(final Message message) throws JMSException {
        if (message instanceof TextMessage text) {
            receive(text);
        } else {
            LOG.warn("Ignored a hearing-resulted message that is not a text message. messageId={}",
                    message.getJMSMessageID());
        }
    }

    private void receive(final TextMessage message) throws JMSException {
        try {
            final PublicEventEnvelope envelope = PublicEventEnvelope.parse(mapper, message.getText());
            LOG.info("Received hearing-resulted event. hearingId={} hearingDay={} sharedTime={}",
                    envelope.hearingId(), envelope.hearingDay(), envelope.sharedTime());
        } catch (JacksonException | IllegalArgumentException unreadable) {
            LOG.warn("Ignored a hearing-resulted message whose body is not a JSON envelope. "
                    + "messageId={} cause={}", message.getJMSMessageID(), unreadable.getClass().getName());
        }
    }
}
