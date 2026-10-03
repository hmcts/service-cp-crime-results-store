package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;

/**
 * The day's youth flag, three values, recomputed under the day lock (FR-026 to FR-028, research R15):
 * TRUE once any share is TRUE, and it stays TRUE; else NULL if any share is unknown; else FALSE. Every
 * share of the day carries the day's value.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("youth seen")
class YouthSeenIT {

    private static final String DAY = "2026-10-02";

    /** A share whose key details could not be read: its youth value is unknown. */
    private static final String FAILED = "failed";

    /** A share whose one defendant does not state {@code isYouth}. */
    private static final String UNSTATED = "unstated";

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

    @ParameterizedTest(name = "shares {0} -> day {1}")
    @CsvSource({
        "false,                    false",
        "true,                     true",
        "unstated,                 ",
        "failed,                   ",
        "false false,              false",
        "false true,               true",
        "true false,               true",
        "true unstated false,      true",
        "unstated false,           ",
        "false unstated,           ",
        "false failed,             ",
        "unstated true,            true",
        "failed false true false,  true",
    })
    void day_should_carry_the_three_valued_youth_flag_on_every_share(final String shares, final Boolean expected) {
        final List<String> values = Arrays.asList(shares.split(" "));
        for (int hour = 0; hour < values.size(); hour++) {
            storeShare("2026-10-02T1%d:00:00.000Z".formatted(hour), values.get(hour));
        }

        assertThat(daySeen()).isEqualTo(expected);
        assertThat(sharesDaySeen()).hasSize(values.size()).allMatch(value -> Objects.equals(value, expected));
    }

    @Test
    void late_youth_share_should_set_the_flag_on_every_share_of_the_day() {
        storeShare("2026-10-02T12:00:00.000Z", "false");
        storeShare("2026-10-02T13:00:00.000Z", "false");

        storeShare("2026-10-02T11:00:00.000Z", "true");

        assertThat(daySeen()).isTrue();
        assertThat(sharesDaySeen()).hasSize(3).containsOnly(Boolean.TRUE);
    }

    @Test
    void share_of_another_day_should_not_change_this_day_s_flag() {
        storeShare("2026-10-02T10:00:00.000Z", "false");

        final String text = SampleShares.share(hearingId, "2026-10-03", "2026-10-03T10:00:00.000Z", "true", "");
        receipts.recordArrival(SampleShares.arrival("ID:other", text));
        store.store(SampleShares.request("ID:other", text));

        assertThat(daySeen()).isFalse();
        assertThat(sharesDaySeen()).containsExactly(Boolean.FALSE);
    }

    private void storeShare(final String sharedTime, final String youth) {
        messages++;
        final String messageId = "ID:" + messages;
        final String youthJson = UNSTATED.equals(youth) || FAILED.equals(youth) ? null : youth;
        final String text = SampleShares.share(hearingId, DAY, sharedTime, youthJson, "");
        receipts.recordArrival(SampleShares.arrival(messageId, text));
        final StoreRequest read = SampleShares.request(messageId, text);
        final StoreRequest request = FAILED.equals(youth)
                ? new StoreRequest(read.messageId(), read.identity(), read.shareId(), read.sharedDays(),
                        read.checksum(), read.text(),
                        new Projection.Failed("WRONG_TYPE:hearing.isSJPHearing", ExtractionFailureKind.WRONG_TYPE))
                : read;
        store.store(request);
    }

    private Boolean daySeen() {
        return jdbc.sql("SELECT youth_seen FROM hearing_day_head WHERE hearing_id = :hearingId AND hearing_day = :day")
                .param("hearingId", hearingId).param("day", LocalDate.parse(DAY))
                .query((row, rowNumber) -> row.getObject(1, Boolean.class)).list().getFirst();
    }

    private List<Boolean> sharesDaySeen() {
        return new ArrayList<>(jdbc.sql("""
                SELECT day_youth_seen FROM hearing_share WHERE hearing_id = :hearingId AND hearing_day = :day
                 ORDER BY shared_at
                """).param("hearingId", hearingId).param("day", LocalDate.parse(DAY))
                .query((row, rowNumber) -> row.getObject(1, Boolean.class)).list());
    }
}
