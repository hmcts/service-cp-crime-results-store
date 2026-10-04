package uk.gov.hmcts.cp.resultsstore.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.NotShare;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.ReceiptStatus;

@DisplayName("a delivery as its receipt sees it")
class ArrivalTest {

    private static final UUID HEARING_ID = UUID.fromString("6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11");

    private static final String SHARE = """
            {"hearing": {"id": "6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11"}, "hearingDay": "2026-10-02",
             "sharedTime": "2026-10-02T14:19:50.706Z"}""";

    private final ShareIdentityParser parser = new ShareIdentityParser(JsonMapper.builder().build());

    @Test
    void share_should_be_received_with_its_identity_and_no_reason_or_text() {
        final Arrival arrival = new Arrival("ID:1", 1, SHARE, parser.read(SHARE));

        assertThat(arrival.key()).isEqualTo("ID:1");
        assertThat(arrival.status()).isEqualTo(ReceiptStatus.RECEIVED);
        assertThat(arrival.hearingId()).isEqualTo(HEARING_ID);
        assertThat(arrival.hearingDay()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(arrival.sharedAt()).isEqualTo(Instant.parse("2026-10-02T14:19:50.706Z"));
        assertThat(arrival.reason()).isNull();
        assertThat(arrival.keptText()).isNull();
    }

    @Test
    void non_share_should_be_in_its_end_state_with_its_reason_text_and_identity_parts() {
        final NotShare reading = new NotShare(NonShareReason.MISSING_SHARED_TIME, HEARING_ID,
                LocalDate.parse("2026-10-02"), null);

        final Arrival arrival = new Arrival("ID:1", 1, "{}", reading);

        assertThat(arrival.status()).isEqualTo(ReceiptStatus.NO_IDENTITY);
        assertThat(arrival.reason()).isEqualTo("MISSING_SHARED_TIME");
        assertThat(arrival.keptText()).isEqualTo("{}");
        assertThat(arrival.hearingId()).isEqualTo(HEARING_ID);
        assertThat(arrival.hearingDay()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(arrival.sharedAt()).isNull();
    }

    @Test
    void text_with_a_raw_nul_should_not_be_kept() {
        final Arrival arrival = new Arrival("ID:1", 1, "\u0000", NotShare.because(NonShareReason.NUL_CHARACTER));

        assertThat(arrival.keptText()).isNull();
        assertThat(arrival.status()).isEqualTo(ReceiptStatus.UNREADABLE);
    }

    @Test
    void message_without_an_id_should_be_keyed_by_the_checksum_of_its_text_or_of_nothing() {
        assertThat(new Arrival(null, 1, SHARE, parser.read(SHARE)).key())
                .isEqualTo("sha256:" + PayloadChecksum.sha256Hex(SHARE));
        assertThat(new Arrival(null, 1, null, NotShare.because(NonShareReason.NOT_TEXT_MESSAGE)).key())
                .isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
