package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.NotShare;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Share;
import uk.gov.hmcts.cp.resultsstore.config.PublicEventsConfig;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;

/**
 * Receives {@code public.events.hearing.hearing-resulted} from the shared durable subscription.
 *
 * <p>A stub for now: it logs the share's identity fields and acknowledges. Storing the share is the
 * first feature spec's job. Only identifiers are logged, never payload content.
 *
 * <p>A message that is not a share is logged at WARN with its bounded reason and acknowledged rather
 * than rolled back: a malformed body will never parse, and redelivering it would only hold up the
 * subscription.
 */
@Component
public class HearingResultedEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(HearingResultedEventListener.class);

    private final ShareIdentityParser parser;

    public HearingResultedEventListener(final ObjectMapper mapper) {
        this.parser = new ShareIdentityParser(mapper);
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
            LOG.warn("Ignored a hearing-resulted message that is not a share. messageId={} reason={}",
                    message.getJMSMessageID(), NonShareReason.NOT_TEXT_MESSAGE);
        }
    }

    private void receive(final TextMessage message) throws JMSException {
        switch (parser.read(message.getText())) {
            case Share share -> LOG.info("Received hearing-resulted event. hearingId={} hearingDay={} sharedTime={}",
                    share.identity().rawHearingId(), share.identity().rawHearingDay(),
                    share.identity().rawSharedTime());
            case NotShare notShare -> LOG.warn("Ignored a hearing-resulted message that is not a share. "
                    + "messageId={} reason={}", message.getJMSMessageID(), notShare.reason());
        }
    }
}
