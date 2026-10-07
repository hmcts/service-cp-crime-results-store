package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult.Stored;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;

/**
 * The chain of a hearing day's shares, worked out under the day lock (FR-022 to FR-025): latest is
 * the greatest {@code shared_at}, never the arrival order; each share points at the one shared before it.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("share chain")
class ShareChainIT {

    private static final String DAY = "2026-10-02";

    private static final String T1 = "2026-10-02T10:00:00.000Z";

    private static final String T2 = "2026-10-02T11:00:00.000Z";

    private static final String T3 = "2026-10-02T12:00:00.000Z";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcReceiptStore receipts;

    private JdbcShareStore store;

    private UUID hearingId;

    private int messages;

    @DynamicPropertySource
    static void database(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @BeforeEach
    void emptyTables() {
        jdbc.sql("TRUNCATE event_receipt, share_defendant, hearing_share_payload, hearing_share, hearing_day_head")
                .update();
        receipts = new JdbcReceiptStore(jdbc, new TransactionTemplate(transactionManager));
        store = new JdbcShareStore(jdbc, new TransactionTemplate(transactionManager), receipts,
                JdbcShareStore.Timeouts.DEFAULTS);
        hearingId = UUID.randomUUID();
    }

    @Test
    void first_share_of_a_day_should_be_latest_with_no_predecessor() {
        final StoreResult result = storeShare(hearingId, DAY, T1);

        final UUID first = shareId(result);
        assertThat(share(first))
                .containsEntry("is_latest", true)
                .containsEntry("predecessor_share_id", null)
                .containsEntry("arrived_out_of_order", false);
        assertThat(result).isInstanceOfSatisfying(Stored.class, stored -> assertThat(stored.outOfOrder()).isFalse());
        assertThat(dayRow(hearingId, DAY)).containsEntry("latest_share_id", first).containsEntry("share_count", 1);
    }

    @Test
    void newer_share_should_take_over_as_latest_and_point_at_the_old_one() {
        final UUID first = shareId(storeShare(hearingId, DAY, T1));

        final StoreResult result = storeShare(hearingId, DAY, T2);

        final UUID second = shareId(result);
        assertThat(share(first)).containsEntry("is_latest", false).containsEntry("predecessor_share_id", null);
        assertThat(share(second))
                .containsEntry("is_latest", true)
                .containsEntry("predecessor_share_id", first)
                .containsEntry("arrived_out_of_order", false);
        assertThat(result).isInstanceOfSatisfying(Stored.class, stored -> assertThat(stored.outOfOrder()).isFalse());
        assertThat(dayRow(hearingId, DAY)).containsEntry("latest_share_id", second).containsEntry("share_count", 2);
    }

    @Test
    void late_share_should_be_linked_into_its_place_and_leave_the_latest_alone() {
        final UUID first = shareId(storeShare(hearingId, DAY, T1));
        final UUID third = shareId(storeShare(hearingId, DAY, T3));

        final StoreResult result = storeShare(hearingId, DAY, T2);

        final UUID second = shareId(result);
        assertThat(share(first)).containsEntry("predecessor_share_id", null).containsEntry("is_latest", false);
        assertThat(share(second))
                .containsEntry("predecessor_share_id", first)
                .containsEntry("is_latest", false)
                .containsEntry("arrived_out_of_order", true);
        assertThat(share(third))
                .containsEntry("predecessor_share_id", second)
                .containsEntry("is_latest", true)
                .containsEntry("arrived_out_of_order", false);
        assertThat(result).isInstanceOfSatisfying(Stored.class, stored -> assertThat(stored.outOfOrder()).isTrue());
        assertThat(dayRow(hearingId, DAY)).containsEntry("latest_share_id", third).containsEntry("share_count", 3);
    }

    @Test
    void late_share_earlier_than_every_other_should_start_the_chain() {
        final UUID second = shareId(storeShare(hearingId, DAY, T2));

        final UUID first = shareId(storeShare(hearingId, DAY, T1));

        assertThat(share(first))
                .containsEntry("predecessor_share_id", null)
                .containsEntry("is_latest", false)
                .containsEntry("arrived_out_of_order", true);
        assertThat(share(second)).containsEntry("predecessor_share_id", first).containsEntry("is_latest", true);
        assertThat(dayRow(hearingId, DAY)).containsEntry("latest_share_id", second).containsEntry("share_count", 2);
    }

    @Test
    void shares_of_another_day_or_hearing_should_not_join_the_chain() {
        final UUID otherHearing = UUID.randomUUID();
        final UUID first = shareId(storeShare(hearingId, DAY, T1));
        final UUID nextDay = shareId(storeShare(hearingId, "2026-10-03", T3));
        final UUID elsewhere = shareId(storeShare(otherHearing, DAY, T3));

        final UUID second = shareId(storeShare(hearingId, DAY, T2));

        assertThat(share(second)).containsEntry("predecessor_share_id", first).containsEntry("is_latest", true)
                .containsEntry("arrived_out_of_order", false);
        assertThat(share(nextDay)).containsEntry("predecessor_share_id", null).containsEntry("is_latest", true);
        assertThat(share(elsewhere)).containsEntry("predecessor_share_id", null).containsEntry("is_latest", true);
        assertThat(dayRow(hearingId, DAY)).containsEntry("latest_share_id", second).containsEntry("share_count", 2);
        assertThat(dayRow(hearingId, "2026-10-03")).containsEntry("share_count", 1);
        assertThat(dayRow(otherHearing, DAY)).containsEntry("share_count", 1);
    }

    @Test
    void duplicate_should_leave_the_chain_and_the_count_alone() {
        final UUID first = shareId(storeShare(hearingId, DAY, T1));
        final UUID second = shareId(storeShare(hearingId, DAY, T2));

        storeShare(hearingId, DAY, T1);

        assertThat(share(second)).containsEntry("predecessor_share_id", first).containsEntry("is_latest", true);
        assertThat(dayRow(hearingId, DAY)).containsEntry("latest_share_id", second).containsEntry("share_count", 2);
    }

    private StoreResult storeShare(final UUID hearing, final String hearingDay, final String sharedTime) {
        messages++;
        final String messageId = "ID:" + messages;
        final String text = SampleShares.share(hearing, hearingDay, sharedTime);
        receipts.recordArrival(SampleShares.arrival(messageId, text));
        final StoreRequest request = SampleShares.request(messageId, text);
        return store.store(request);
    }

    private static UUID shareId(final StoreResult result) {
        return switch (result) {
            case Stored stored -> stored.shareId();
            case StoreResult.Duplicate duplicate -> duplicate.existingShareId();
        };
    }

    private Map<String, Object> share(final UUID shareId) {
        return jdbc.sql("SELECT * FROM hearing_share WHERE share_id = :shareId")
                .param("shareId", shareId).query().singleRow();
    }

    private Map<String, Object> dayRow(final UUID hearing, final String hearingDay) {
        return jdbc.sql("SELECT * FROM hearing_day_head WHERE hearing_id = :hearingId AND hearing_day = :day")
                .param("hearingId", hearing).param("day", LocalDate.parse(hearingDay)).query().singleRow();
    }
}
