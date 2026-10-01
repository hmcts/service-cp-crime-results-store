package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;

class HearingResultedEventListenerTest {

    private final HearingResultedEventListener listener =
            new HearingResultedEventListener(JsonMapper.builder().build());

    private CapturedLog log;

    @BeforeEach
    void capture() {
        log = CapturedLog.forClass(HearingResultedEventListener.class);
    }

    @AfterEach
    void release() {
        log.close();
    }

    @Test
    void a_hearing_resulted_event_should_be_logged_at_info_with_its_identity() throws JMSException {
        listener.onHearingResulted(text("""
                {"hearing": {"id": "a1b2"}, "hearingDay": "2026-09-30",
                 "sharedTime": "2026-09-30T15:04:05.000Z", "hearing_detail": "not logged"}"""));

        assertThat(log.events()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("hearingId=a1b2", "hearingDay=2026-09-30",
                            "sharedTime=2026-09-30T15:04:05.000Z")
                    .doesNotContain("not logged");
        });
    }

    @Test
    void a_body_that_is_not_json_should_be_acknowledged_with_a_warning() throws JMSException {
        listener.onHearingResulted(text("not json"));

        assertThat(log.events()).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
    }

    @Test
    void a_body_that_is_not_an_object_should_be_acknowledged_with_a_warning() throws JMSException {
        listener.onHearingResulted(text("\"just a string\""));

        assertThat(log.events()).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
    }

    @Test
    void a_message_that_is_not_text_should_be_acknowledged_with_a_warning() throws JMSException {
        final BytesMessage message = mock(BytesMessage.class);
        when(message.getJMSMessageID()).thenReturn("ID:1");

        listener.onHearingResulted(message);

        assertThat(log.events()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("messageId=ID:1");
        });
    }

    private static TextMessage text(final String body) throws JMSException {
        final TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn(body);
        when(message.getJMSMessageID()).thenReturn("ID:1");
        return message;
    }
}
