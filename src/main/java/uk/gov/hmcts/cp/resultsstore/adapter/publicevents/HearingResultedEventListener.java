package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jms.annotation.JmsListener;
import uk.gov.hmcts.cp.resultsstore.application.IntakeCommand;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.application.IntakeResult;
import uk.gov.hmcts.cp.resultsstore.application.IntakeService;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.config.PublicEventsConfig;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;

/**
 * Receives {@code public.events.hearing.hearing-resulted} from the shared durable subscription and
 * hands each message to intake (FR-001, FR-006).
 *
 * <p>This is the one place an exception becomes a broker decision. A normal return lets the
 * container commit the transacted session, which acknowledges the message; intake returns only after
 * its transactions commit. A {@link RetryableIntakeException}, or any other runtime failure, is
 * rethrown after the capped pause, so the container rolls the session back and the broker redelivers.
 * A {@code JMSException} reading the message escapes at once, before intake runs; the delivery has
 * already been counted as received.
 *
 * <p>The logging context holds the message id from the start and the share's ids once intake returns,
 * and is cleared after every message (FR-041). Log lines hold ids, counts and bounded codes only:
 * never the message text, and never an exception's message.
 */
public class HearingResultedEventListener {

    /** The broker's delivery count, 1 on the first delivery. */
    private static final String DELIVERY_COUNT = "JMSXDeliveryCount";

    private static final Logger LOG = LoggerFactory.getLogger(HearingResultedEventListener.class);

    private static final String MDC_MESSAGE_ID = "messageId";

    private static final String MDC_SHARE_ID = "shareId";

    private static final String MDC_HEARING_ID = "hearingId";

    private static final String MDC_HEARING_DAY = "hearingDay";

    private static final String MDC_SHARED_TIME = "sharedTime";

    private static final List<String> MDC_KEYS =
            List.of(MDC_MESSAGE_ID, MDC_SHARE_ID, MDC_HEARING_ID, MDC_HEARING_DAY, MDC_SHARED_TIME);

    private static final int FIRST_DELIVERY = 1;

    private final IntakeService intake;

    private final RedeliveryPause pause;

    private final IntakeObserver observer;

    /**
     * Creates the listener.
     *
     * @param intake   the intake service
     * @param pause    the pause before a rollback
     * @param observer the metrics port, told of every delivery
     */
    public HearingResultedEventListener(final IntakeService intake, final RedeliveryPause pause,
            final IntakeObserver observer) {
        this.intake = intake;
        this.pause = pause;
        this.observer = observer;
    }

    /**
     * Takes in one message.
     *
     * @param message the message
     * @throws JMSException when the message cannot be read; the session is rolled back
     */
    @JmsListener(
            destination = "${resultsstore.publicevents.topic}",
            subscription = "${resultsstore.publicevents.subscription}",
            selector = "${resultsstore.publicevents.selector}",
            containerFactory = PublicEventsConfig.LISTENER_CONTAINER_FACTORY)
    // Pause-then-rethrow: nothing is swallowed. Any runtime failure rolls the message back, so each one
    // waits the capped pause before it escapes (orchestrator ruling, T011). Errors are not caught.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void onHearingResulted(final Message message) throws JMSException {
        // Every delivery, before any field is read: one whose fields cannot be read is still counted.
        observer.received();
        final String messageId = message.getJMSMessageID();
        final int deliveryCount = deliveryCount(message);
        try {
            put(MDC_MESSAGE_ID, messageId);
            final IntakeCommand command = message instanceof TextMessage text
                    ? IntakeCommand.ofText(messageId, deliveryCount, text.getText())
                    : IntakeCommand.ofNotText(messageId, deliveryCount);
            final IntakeResult result = intake.receive(command);
            putIds(result);
            LOG.info("Intake finished. outcome={} messageId={} shareId={} hearingId={} deliveryCount={}",
                    result.outcome().tag(), result.messageId(), result.shareId(), result.hearingId(), deliveryCount);
        } catch (final RetryableIntakeException failure) {
            // The pause asked for: an interrupt can end the wait early, so it is not logged as time elapsed.
            final Duration requested = pause.pause(deliveryCount);
            LOG.warn("Intake failed and is rolled back for the broker to redeliver. stage={} cause={} "
                            + "messageId={} deliveryCount={} requestedPause={}",
                    failure.getStage().tag(), failure.getFailureCause().tag(), messageId, deliveryCount, requested);
            throw failure;
        } catch (final RuntimeException failure) {
            // Counted by intake as cause other. It is rolled back and redelivered like a retryable failure,
            // so it waits the same pause; only its class is logged, never its message.
            final Duration requested = pause.pause(deliveryCount);
            LOG.warn("Intake failed unexpectedly and is rolled back for the broker to redeliver. cause={} "
                            + "exception={} messageId={} deliveryCount={} requestedPause={}",
                    IntakeFailureCause.OTHER.tag(), failure.getClass().getName(), messageId, deliveryCount,
                    requested);
            throw failure;
        } finally {
            MDC_KEYS.forEach(MDC::remove);
        }
    }

    /** The broker's delivery count; missing or not a number counts as the first delivery. */
    private static int deliveryCount(final Message message) throws JMSException {
        return message.getObjectProperty(DELIVERY_COUNT) instanceof Integer count ? count : FIRST_DELIVERY;
    }

    private static void putIds(final IntakeResult result) {
        put(MDC_MESSAGE_ID, result.messageId());
        put(MDC_SHARE_ID, result.shareId());
        put(MDC_HEARING_ID, result.hearingId());
        put(MDC_HEARING_DAY, result.hearingDay());
        put(MDC_SHARED_TIME, result.sharedAt());
    }

    private static void put(final String key, final Object value) {
        if (value != null) {
            MDC.put(key, Objects.toString(value));
        }
    }
}
