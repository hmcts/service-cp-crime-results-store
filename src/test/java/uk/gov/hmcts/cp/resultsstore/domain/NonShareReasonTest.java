package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NonShareReasonTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "NOT_TEXT_MESSAGE,    UNREADABLE,  not_text_message,    unreadable",
        "NUL_CHARACTER,       UNREADABLE,  nul_character,       unreadable",
        "NOT_JSON,            UNREADABLE,  not_json,            unreadable",
        "NOT_OBJECT,          UNREADABLE,  not_object,          unreadable",
        "MISSING_HEARING_ID,  NO_IDENTITY, missing_hearing_id,  no_identity",
        "INVALID_HEARING_ID,  NO_IDENTITY, invalid_hearing_id,  no_identity",
        "MISSING_HEARING_DAY, NO_IDENTITY, missing_hearing_day, no_identity",
        "INVALID_HEARING_DAY, NO_IDENTITY, invalid_hearing_day, no_identity",
        "MISSING_SHARED_TIME, NO_IDENTITY, missing_shared_time, no_identity",
        "INVALID_SHARED_TIME, NO_IDENTITY, invalid_shared_time, no_identity"
    })
    void reason_should_map_to_its_status_and_metric_tags(final NonShareReason reason,
            final ReceiptStatus status, final String tag, final String statusTag) {
        assertThat(reason.status()).isEqualTo(status);
        assertThat(reason.tag()).isEqualTo(tag);
        assertThat(reason.statusTag()).isEqualTo(statusTag);
    }

    @Test
    void every_reason_code_should_fit_the_receipt_reason_column() {
        assertThat(Arrays.stream(NonShareReason.values()).map(Enum::name))
                .allSatisfy(code -> assertThat(code).hasSizeLessThanOrEqualTo(120));
    }
}
