package uk.gov.hmcts.cp.resultsstore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Duplicate;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Stored;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeOutcome;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.ReceiptStatus;
import uk.gov.hmcts.cp.resultsstore.domain.ShareId;
import uk.gov.hmcts.cp.resultsstore.domain.SharedDays;

@ExtendWith(MockitoExtension.class)
class IntakeServiceTest {

    private static final String MESSAGE_ID = "ID:1";

    private static final UUID HEARING_ID = UUID.fromString("6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11");

    private static final String HEARING_DAY = "2026-10-02";

    private static final String SHARED_TIME = "2026-10-02T14:19:50.706Z";

    private static final Instant SHARED_AT = Instant.parse(SHARED_TIME);

    private static final UUID SHARE_ID = ShareId.from(HEARING_ID.toString(), HEARING_DAY, SHARED_TIME);

    private static final String SHARE = """
            {"hearing": {"id": "%s", "courtCentre": {"id": "9d2e4f6a-1b3c-4d5e-8f70-a1b2c3d4e5f6"}},
             "hearingDay": "%s", "sharedTime": "%s"}""".formatted(HEARING_ID, HEARING_DAY, SHARED_TIME);

    /** A share whose key details cannot be read: the court centre id is not a UUID. */
    private static final String SHARE_WITH_A_BAD_COURT_CENTRE = """
            {"hearing": {"id": "%s", "courtCentre": {"id": "not-a-uuid"}},
             "hearingDay": "%s", "sharedTime": "%s"}""".formatted(HEARING_ID, HEARING_DAY, SHARED_TIME);

    @Mock
    private EventReceipts receipts;

    @Mock
    private ShareStore shareStore;

    @Mock
    private IntakeObserver observer;

    @Mock
    private KeyDetailsExtractor mockedExtractor;

    private IntakeService service() {
        return new IntakeService(new ShareIdentityParser(JsonMapper.builder().build()), new KeyDetailsExtractor(),
                receipts, shareStore, observer);
    }

    /** The service with a mocked extractor, to show where extraction is not run. */
    private IntakeService serviceWithAMockedExtractor() {
        return new IntakeService(new ShareIdentityParser(JsonMapper.builder().build()), mockedExtractor,
                receipts, shareStore, observer);
    }

    private static ReceiptState received(final boolean inserted) {
        return new ReceiptState(MESSAGE_ID, ReceiptStatus.RECEIVED, null, inserted ? 1 : 2, inserted);
    }

    @Nested
    @DisplayName("a non-share")
    class ANonShare {

        @Test
        void receive_of_an_unreadable_body_should_record_it_and_never_touch_the_store() {
            when(receipts.recordArrival(any())).thenReturn(
                    new ReceiptState(MESSAGE_ID, ReceiptStatus.UNREADABLE, null, 1, true));

            final IntakeResult result = service().receive(IntakeCommand.ofText(MESSAGE_ID, 1, "not json"));

            assertThat(result).isEqualTo(new IntakeResult(IntakeOutcome.NOT_A_SHARE, MESSAGE_ID, null, null, null,
                    null));
            final ArgumentCaptor<Arrival> arrival = ArgumentCaptor.forClass(Arrival.class);
            verify(receipts).recordArrival(arrival.capture());
            assertThat(arrival.getValue().status()).isEqualTo(ReceiptStatus.UNREADABLE);
            assertThat(arrival.getValue().keptText()).isEqualTo("not json");
            verifyNoInteractions(shareStore);
            final InOrder order = inOrder(observer, receipts);
            order.verify(observer).received();
            order.verify(receipts).recordArrival(any());
            order.verify(observer).notShare(NonShareReason.NOT_JSON);
            verifyNoMoreInteractions(observer);
        }

        @Test
        void receive_of_a_message_that_is_not_text_should_be_recorded_as_not_a_text_message() {
            when(receipts.recordArrival(any())).thenReturn(
                    new ReceiptState(MESSAGE_ID, ReceiptStatus.UNREADABLE, null, 1, true));

            final IntakeResult result = service().receive(IntakeCommand.ofNotText(MESSAGE_ID, 1));

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.NOT_A_SHARE);
            final ArgumentCaptor<Arrival> arrival = ArgumentCaptor.forClass(Arrival.class);
            verify(receipts).recordArrival(arrival.capture());
            assertThat(arrival.getValue().reason()).isEqualTo("NOT_TEXT_MESSAGE");
            verify(observer).notShare(NonShareReason.NOT_TEXT_MESSAGE);
            verifyNoInteractions(shareStore);
        }

        @Test
        void receive_of_a_body_with_part_of_its_identity_should_report_the_parts_read() {
            when(receipts.recordArrival(any())).thenReturn(
                    new ReceiptState(MESSAGE_ID, ReceiptStatus.NO_IDENTITY, null, 1, true));
            final String text = """
                    {"hearing": {"id": "%s"}, "hearingDay": "%s"}""".formatted(HEARING_ID, HEARING_DAY);

            final IntakeResult result = service().receive(IntakeCommand.ofText(MESSAGE_ID, 1, text));

            assertThat(result).isEqualTo(new IntakeResult(IntakeOutcome.NOT_A_SHARE, MESSAGE_ID, null, HEARING_ID,
                    LocalDate.parse(HEARING_DAY), null));
            verify(observer).notShare(NonShareReason.MISSING_SHARED_TIME);
        }

        @Test
        void receive_of_a_non_share_should_not_read_the_key_details() {
            when(receipts.recordArrival(any())).thenReturn(
                    new ReceiptState(MESSAGE_ID, ReceiptStatus.UNREADABLE, null, 1, true));

            serviceWithAMockedExtractor().receive(IntakeCommand.ofText(MESSAGE_ID, 1, "not json"));

            verifyNoInteractions(mockedExtractor);
        }

        @Test
        void redelivery_of_a_recorded_non_share_should_be_already_settled_and_not_counted_again() {
            when(receipts.recordArrival(any())).thenReturn(
                    new ReceiptState(MESSAGE_ID, ReceiptStatus.UNREADABLE, null, 2, false));

            final IntakeResult result = service().receive(IntakeCommand.ofText(MESSAGE_ID, 2, "not json"));

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.ALREADY_SETTLED);
            verify(observer).alreadySettled();
            verify(observer, never()).notShare(any());
            verifyNoInteractions(shareStore);
        }
    }

    @Nested
    @DisplayName("a share")
    class AShare {

        @Test
        void receive_should_store_after_the_receipt_and_report_after_the_store_returns() {
            when(receipts.recordArrival(any())).thenReturn(received(true));
            final Instant storedAt = SHARED_AT.plusSeconds(3);
            when(shareStore.store(any())).thenReturn(new Stored(SHARE_ID, storedAt, false, false));

            final IntakeResult result = service().receive(IntakeCommand.ofText(MESSAGE_ID, 1, SHARE));

            assertThat(result).isEqualTo(new IntakeResult(IntakeOutcome.STORED, MESSAGE_ID, SHARE_ID, HEARING_ID,
                    LocalDate.parse(HEARING_DAY), SHARED_AT));
            final InOrder order = inOrder(observer, receipts, shareStore);
            order.verify(observer).received();
            order.verify(receipts).recordArrival(any());
            order.verify(shareStore).store(any());
            order.verify(observer).stored(false, Duration.ofSeconds(3));
            verifyNoMoreInteractions(observer);
        }

        @Test
        void receive_should_hand_the_store_the_share_as_received_with_its_key_details() {
            when(receipts.recordArrival(any())).thenReturn(received(true));
            when(shareStore.store(any())).thenReturn(new Stored(SHARE_ID, SHARED_AT, false, false));

            service().receive(IntakeCommand.ofText(MESSAGE_ID, 1, SHARE));

            final ArgumentCaptor<StoreRequest> request = ArgumentCaptor.forClass(StoreRequest.class);
            verify(shareStore).store(request.capture());
            assertThat(request.getValue().messageId()).isEqualTo(MESSAGE_ID);
            assertThat(request.getValue().shareId()).isEqualTo(SHARE_ID);
            assertThat(request.getValue().identity().hearingId()).isEqualTo(HEARING_ID);
            assertThat(request.getValue().identity().rawSharedTime()).isEqualTo(SHARED_TIME);
            assertThat(request.getValue().sharedDays()).isEqualTo(SharedDays.from(SHARED_AT));
            assertThat(request.getValue().text()).isEqualTo(SHARE);
            assertThat(request.getValue().checksum()).isEqualTo(PayloadChecksum.sha256Hex(SHARE));
            assertThat(request.getValue().projection()).isInstanceOfSatisfying(Projection.Extracted.class,
                    extracted -> assertThat(extracted.keyDetails().courtCentreId())
                            .isEqualTo(UUID.fromString("9d2e4f6a-1b3c-4d5e-8f70-a1b2c3d4e5f6")));
        }

        @Test
        void receive_with_unreadable_key_details_should_still_store_and_report_the_failure_after() {
            when(receipts.recordArrival(any())).thenReturn(received(true));
            when(shareStore.store(any())).thenReturn(new Stored(SHARE_ID, SHARED_AT, false, false));

            final IntakeResult result = service().receive(
                    IntakeCommand.ofText(MESSAGE_ID, 1, SHARE_WITH_A_BAD_COURT_CENTRE));

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.STORED);
            final ArgumentCaptor<StoreRequest> request = ArgumentCaptor.forClass(StoreRequest.class);
            final InOrder order = inOrder(shareStore, observer);
            order.verify(shareStore).store(request.capture());
            order.verify(observer).extractionFailed(ExtractionStage.INTAKE, ExtractionFailureKind.INVALID_UUID);
            assertThat(request.getValue().projection()).isInstanceOf(Projection.Failed.class);
        }

        @Test
        void receive_of_a_late_share_should_report_it_out_of_order_with_a_lag_never_below_zero() {
            when(receipts.recordArrival(any())).thenReturn(received(true));
            when(shareStore.store(any())).thenReturn(new Stored(SHARE_ID, SHARED_AT.minusSeconds(5), true, false));

            service().receive(IntakeCommand.ofText(MESSAGE_ID, 1, SHARE));

            verify(observer).stored(true, Duration.ZERO);
        }

        @Test
        void receive_whose_parsed_copy_was_skipped_should_report_it() {
            when(receipts.recordArrival(any())).thenReturn(received(true));
            when(shareStore.store(any())).thenReturn(new Stored(SHARE_ID, SHARED_AT, false, true));

            service().receive(IntakeCommand.ofText(MESSAGE_ID, 1, SHARE));

            verify(observer).parsedCopySkipped();
        }

        @Test
        void receive_of_a_share_already_stored_should_be_a_duplicate_pointing_at_the_existing_share() {
            when(receipts.recordArrival(any())).thenReturn(received(true));
            final UUID existing = UUID.randomUUID();
            when(shareStore.store(any())).thenReturn(new Duplicate(existing));

            final IntakeResult result = service().receive(
                    IntakeCommand.ofText(MESSAGE_ID, 1, SHARE_WITH_A_BAD_COURT_CENTRE));

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.DUPLICATE);
            assertThat(result.shareId()).isEqualTo(existing);
            verify(observer).duplicate();
            verify(observer, never()).stored(anyBoolean(), any());
            verify(observer, never()).extractionFailed(any(), any());
        }

        @Test
        void redelivery_whose_receipt_is_still_received_should_store_again() {
            when(receipts.recordArrival(any())).thenReturn(received(false));
            when(shareStore.store(any())).thenReturn(new Stored(SHARE_ID, SHARED_AT, false, false));

            final IntakeResult result = service().receive(IntakeCommand.ofText(MESSAGE_ID, 2, SHARE));

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.STORED);
        }

        @Test
        void redelivery_whose_receipt_is_settled_should_short_circuit_without_storing() {
            when(receipts.recordArrival(any())).thenReturn(
                    new ReceiptState(MESSAGE_ID, ReceiptStatus.STORED, SHARE_ID, 2, false));

            final IntakeResult result = service().receive(IntakeCommand.ofText(MESSAGE_ID, 2, SHARE));

            assertThat(result).isEqualTo(new IntakeResult(IntakeOutcome.ALREADY_SETTLED, MESSAGE_ID, SHARE_ID,
                    HEARING_ID, LocalDate.parse(HEARING_DAY), SHARED_AT));
            verifyNoInteractions(shareStore);
            verify(observer).alreadySettled();
        }

        @Test
        void redelivery_whose_receipt_is_settled_should_not_read_the_key_details() {
            when(receipts.recordArrival(any())).thenReturn(
                    new ReceiptState(MESSAGE_ID, ReceiptStatus.STORED, SHARE_ID, 2, false));

            serviceWithAMockedExtractor().receive(IntakeCommand.ofText(MESSAGE_ID, 2, SHARE));

            verifyNoInteractions(mockedExtractor);
        }

        @Test
        void receive_of_a_message_with_no_id_should_use_the_receipt_key_and_report_the_missing_id() {
            final String key = "sha256:" + PayloadChecksum.sha256Hex(SHARE);
            when(receipts.recordArrival(any())).thenReturn(
                    new ReceiptState(key, ReceiptStatus.RECEIVED, null, 1, true));
            when(shareStore.store(any())).thenReturn(new Stored(SHARE_ID, SHARED_AT, false, false));

            final IntakeResult result = service().receive(IntakeCommand.ofText(null, 1, SHARE));

            assertThat(result.messageId()).isEqualTo(key);
            final ArgumentCaptor<StoreRequest> request = ArgumentCaptor.forClass(StoreRequest.class);
            verify(shareStore).store(request.capture());
            assertThat(request.getValue().messageId()).isEqualTo(key);
            verify(observer).messageIdMissing();
        }

        @Test
        void receive_of_a_message_with_an_id_should_not_report_a_missing_id() {
            when(receipts.recordArrival(any())).thenReturn(received(true));
            when(shareStore.store(any())).thenReturn(new Stored(SHARE_ID, SHARED_AT, false, false));

            service().receive(IntakeCommand.ofText(MESSAGE_ID, 1, SHARE));

            verify(observer, never()).messageIdMissing();
        }
    }

    @Nested
    @DisplayName("a failure")
    class AFailure {

        @Test
        void receipt_failure_should_propagate_counted_once_and_never_store() {
            final RetryableIntakeException failure = failure(IntakeStage.RECEIPT, IntakeFailureCause.LOCK_TIMEOUT);
            when(receipts.recordArrival(any())).thenThrow(failure);

            assertThatThrownBy(() -> service().receive(IntakeCommand.ofText(MESSAGE_ID, 1, SHARE)))
                    .isSameAs(failure);

            verify(observer).received();
            verify(observer).intakeFailed(IntakeStage.RECEIPT, IntakeFailureCause.LOCK_TIMEOUT);
            verifyNoMoreInteractions(observer);
            verifyNoInteractions(shareStore);
        }

        @Test
        void store_failure_should_propagate_counted_once_with_nothing_else_reported() {
            when(receipts.recordArrival(any())).thenReturn(received(true));
            final RetryableIntakeException failure = failure(IntakeStage.STORE, IntakeFailureCause.STATEMENT_TIMEOUT);
            when(shareStore.store(any())).thenThrow(failure);

            assertThatThrownBy(() -> service().receive(
                    IntakeCommand.ofText(MESSAGE_ID, 1, SHARE_WITH_A_BAD_COURT_CENTRE)))
                    .isSameAs(failure);

            verify(observer).received();
            verify(observer).intakeFailed(IntakeStage.STORE, IntakeFailureCause.STATEMENT_TIMEOUT);
            verifyNoMoreInteractions(observer);
        }

        @Test
        void unexpected_receipt_failure_should_propagate_unchanged_counted_once_as_other_and_never_store() {
            final IllegalStateException failure = new IllegalStateException("not a database failure");
            when(receipts.recordArrival(any())).thenThrow(failure);

            assertThatThrownBy(() -> service().receive(IntakeCommand.ofText(MESSAGE_ID, 1, SHARE)))
                    .isSameAs(failure);

            verify(observer).received();
            verify(observer).intakeFailed(IntakeStage.RECEIPT, IntakeFailureCause.OTHER);
            verifyNoMoreInteractions(observer);
            verifyNoInteractions(shareStore);
        }

        @Test
        void unexpected_store_failure_should_propagate_unchanged_counted_once_as_other() {
            when(receipts.recordArrival(any())).thenReturn(received(true));
            final IllegalStateException failure = new IllegalStateException("not a database failure");
            when(shareStore.store(any())).thenThrow(failure);

            assertThatThrownBy(() -> service().receive(IntakeCommand.ofText(MESSAGE_ID, 1, SHARE)))
                    .isSameAs(failure);

            verify(observer).received();
            verify(observer).intakeFailed(IntakeStage.STORE, IntakeFailureCause.OTHER);
            verifyNoMoreInteractions(observer);
        }

        private RetryableIntakeException failure(final IntakeStage stage, final IntakeFailureCause cause) {
            return new RetryableIntakeException(stage, cause, new SQLException("refused"));
        }
    }
}
