package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The single table from reason to status (contracts/read-api.md §6). */
@DisplayName("problem reasons")
class ProblemReasonTest {

    @ParameterizedTest
    @EnumSource(ProblemReason.class)
    void every_reason_should_map_to_one_status_and_a_lower_snake_code(final ProblemReason reason) {
        assertThat(reason.code()).matches("[a-z]+(_[a-z]+)*");
        assertThat(reason.status()).isIn(400, 401, 403, 404, 405, 406, 415, 500, 503);
    }

    @Test
    void the_reasons_should_be_exactly_the_contract_list() {
        assertThat(Arrays.stream(ProblemReason.values()).map(reason -> reason.code() + " " + reason.status()))
                .containsExactly(
                        "unknown_parameter 400", "repeated_parameter 400", "conflicting_parameters 400",
                        "missing_parameter 400", "invalid_stored_after_seq 400", "limit_out_of_range 400",
                        "invalid_day_youth_seen 400", "invalid_court_centre_id 400", "invalid_shared_day 400",
                        "day_range_reversed 400", "day_range_too_long 400", "invalid_shared_from 400",
                        "invalid_shared_to 400", "time_range_reversed 400", "time_range_too_long 400",
                        "invalid_latest_only 400", "invalid_cursor 400", "invalid_share_id 400",
                        "invalid_hearing_id 400", "invalid_hearing_day 400", "bad_request 400",
                        "unauthenticated 401", "forbidden 403", "route_not_found 404", "share_not_found 404",
                        "hearing_day_not_found 404", "method_not_allowed 405", "not_acceptable 406",
                        "unsupported_content_type 415", "internal_error 500", "store_unavailable 503");
    }

    /** What {@code /error} names a status it did not choose (research R13). */
    @ParameterizedTest
    @CsvSource({"401, UNAUTHENTICATED", "403, FORBIDDEN", "400, BAD_REQUEST", "404, BAD_REQUEST",
        "405, BAD_REQUEST", "499, BAD_REQUEST", "500, INTERNAL_ERROR", "502, INTERNAL_ERROR", "503, INTERNAL_ERROR",
        "200, INTERNAL_ERROR", "399, INTERNAL_ERROR", "600, INTERNAL_ERROR", "0, INTERNAL_ERROR"})
    void an_error_status_should_map_to_its_bounded_reason(final int status, final ProblemReason reason) {
        assertThat(ProblemReason.forErrorStatus(status)).isEqualTo(reason);
    }
}
