package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import uk.gov.hmcts.cp.resultsstore.application.IntakeCommand;
import uk.gov.hmcts.cp.resultsstore.application.IntakeResult;
import uk.gov.hmcts.cp.resultsstore.application.IntakeService;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeOutcome;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;

class HearingResultedEventListenerTest {

    private static final String MESSAGE_ID = "ID:1";

    private static final UUID SHARE_ID = UUID.fromString("3ca3dfde-49ec-529d-abd7-76818b64f17c");

    private static final UUID HEARING_ID = UUID.fromString("6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11");

    private static final String MARKER = "payload text never logged";

    private static final IntakeResult STORED = new IntakeResult(IntakeOutcome.STORED, MESSAGE_ID, SHARE_ID,
            HEARING_ID, LocalDate.parse("2026-10-02"), Instant.parse("2026-10-02T14:19:50.706Z"));

    private final IntakeService intake = mock(IntakeService.class);

    private final List<Duration> slept = new ArrayList<>();

    private final HearingResultedEventListener listener = new HearingResultedEventListener(intake,
            new RedeliveryPause(slept::add, true, Duration.ofSeconds(30)));

    private CapturedLog log;

    @BeforeEach
    void capture() {
        log = CapturedLog.forClass(HearingResultedEventListener.class);
        MDC.clear();
    }

    @AfterEach
    void release() {
        log.close();
        MDC.clear();
    }

    @Test
    void text_message_should_reach_intake_with_its_id_delivery_count_and_text() throws JMSException {
        when(intake.receive(any())).thenReturn(STORED);

        listener.onHearingResulted(text(MARKER, 3));

        verify(intake).receive(IntakeCommand.ofText(MESSAGE_ID, 3, MARKER));
    }

    @Test
    void text_message_with_no_text_should_reach_intake_with_null_text() throws JMSException {
        when(intake.receive(any())).thenReturn(STORED);

        listener.onHearingResulted(text(null, 1));

        verify(intake).receive(IntakeCommand.ofText(MESSAGE_ID, 1, null));
    }

    @Test
    void message_that_is_not_text_should_reach_intake_as_not_a_text_message() throws JMSException {
        when(intake.receive(any())).thenReturn(STORED);
        final BytesMessage message = mock(BytesMessage.class);
        when(message.getJMSMessageID()).thenReturn(MESSAGE_ID);
        when(message.getObjectProperty("JMSXDeliveryCount")).thenReturn(2);

        listener.onHearingResulted(message);

        verify(intake).receive(IntakeCommand.ofNotText(MESSAGE_ID, 2));
    }

    @Test
    void message_with_no_delivery_count_should_count_as_the_first_delivery() throws JMSException {
        when(intake.receive(any())).thenReturn(STORED);
        final TextMessage message = text(MARKER, 1);
        when(message.getObjectProperty("JMSXDeliveryCount")).thenReturn(null);

        listener.onHearingResulted(message);

        verify(intake).receive(IntakeCommand.ofText(MESSAGE_ID, 1, MARKER));
    }

    @Test
    void message_with_an_unreadable_delivery_count_should_count_as_the_first_delivery() throws JMSException {
        when(intake.receive(any())).thenReturn(STORED);
        final TextMessage message = text(MARKER, 1);
        when(message.getObjectProperty("JMSXDeliveryCount")).thenReturn("three");

        listener.onHearingResulted(message);

        verify(intake).receive(IntakeCommand.ofText(MESSAGE_ID, 1, MARKER));
    }

    @Test
    void message_with_no_id_should_reach_intake_with_none() throws JMSException {
        when(intake.receive(any())).thenReturn(STORED);
        final TextMessage message = text(MARKER, 1);
        when(message.getJMSMessageID()).thenReturn(null);

        listener.onHearingResulted(message);

        verify(intake).receive(IntakeCommand.ofText(null, 1, MARKER));
    }

    @Test
    void logging_context_should_hold_the_message_id_during_intake_and_the_ids_after_it() throws JMSException {
        final Map<String, String> duringIntake = new HashMap<>();
        when(intake.receive(any())).thenAnswer(invocation -> {
            duringIntake.putAll(MDC.getCopyOfContextMap());
            return STORED;
        });

        listener.onHearingResulted(text(MARKER, 1));

        assertThat(duringIntake).containsEntry("messageId", MESSAGE_ID);
        assertThat(log.events()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getMDCPropertyMap())
                    .containsEntry("messageId", MESSAGE_ID)
                    .containsEntry("shareId", SHARE_ID.toString())
                    .containsEntry("hearingId", HEARING_ID.toString())
                    .containsEntry("hearingDay", "2026-10-02")
                    .containsEntry("sharedTime", "2026-10-02T14:19:50.706Z");
            assertThat(event.getFormattedMessage()).contains("outcome=stored", "shareId=" + SHARE_ID);
        });
        assertThat(MDC.getCopyOfContextMap()).as("cleared after the message").isNullOrEmpty();
    }

    @Test
    void non_share_should_be_logged_with_ids_only() throws JMSException {
        when(intake.receive(any())).thenReturn(new IntakeResult(IntakeOutcome.NOT_A_SHARE, MESSAGE_ID, null, null,
                null, null));

        listener.onHearingResulted(text(MARKER, 1));

        assertThat(log.events()).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("outcome=not_a_share").doesNotContain(MARKER);
            assertThat(event.getMDCPropertyMap()).containsOnlyKeys("messageId");
        });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void retryable_failure_should_pause_then_rethrow_with_ids_only_in_the_log() throws JMSException {
        final RetryableIntakeException failure = new RetryableIntakeException(IntakeStage.STORE,
                IntakeFailureCause.LOCK_TIMEOUT, new SQLException(MARKER, "55P03"));
        when(intake.receive(any())).thenThrow(failure);

        assertThatThrownBy(() -> listener.onHearingResulted(text(MARKER, 3))).isSameAs(failure);

        assertThat(slept).containsExactly(Duration.ofSeconds(8));
        final ILoggingEvent event = log.events().getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("stage=store", "cause=lock_timeout", "deliveryCount=3", "messageId=" + MESSAGE_ID)
                .doesNotContain(MARKER);
        assertThat(event.getThrowableProxy()).as("no exception text in the log").isNull();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void retryable_failure_on_a_late_delivery_should_pause_no_longer_than_the_cap() throws JMSException {
        when(intake.receive(any())).thenThrow(new RetryableIntakeException(IntakeStage.RECEIPT,
                IntakeFailureCause.DATABASE, new SQLException("down")));

        assertThatThrownBy(() -> listener.onHearingResulted(text(MARKER, 9)))
                .isInstanceOf(RetryableIntakeException.class);

        assertThat(slept).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void other_failure_should_escape_without_a_pause_and_clear_the_context() throws JMSException {
        final IllegalStateException failure = new IllegalStateException("bug");
        when(intake.receive(any())).thenThrow(failure);

        assertThatThrownBy(() -> listener.onHearingResulted(text(MARKER, 2))).isSameAs(failure);

        assertThat(slept).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private static TextMessage text(final String body, final int deliveryCount) throws JMSException {
        final TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn(body);
        when(message.getJMSMessageID()).thenReturn(MESSAGE_ID);
        when(message.getObjectProperty("JMSXDeliveryCount")).thenReturn(deliveryCount);
        return message;
    }
}
