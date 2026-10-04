package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;

/** The strict parameter rules of contracts/read-api.md §2.2 and §4 (FR-002, FR-003, FR-011, FR-026, FR-027). */
@DisplayName("share parameters")
class ShareParametersTest {

    private static final String COURT = "courtCentreId=2b3c4d5e-0000-4000-8000-000000000002";

    private static final String DAYS = "&sharedDayFrom=2026-10-01&sharedDayTo=2026-10-03";

    private static final String TIMES = "&sharedFrom=2026-10-02T23:00:00Z&sharedTo=2026-10-03T17:00:00Z";

    private static final String SHARE_ID = "6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b";

    private static Optional<ProblemReason> pull(final String query) {
        return ShareParameters.check(ApiRoute.PULL_SHARES, query, Map.of());
    }

    private static Optional<ProblemReason> search(final String query) {
        return ShareParameters.check(ApiRoute.SEARCH_SHARES, query, Map.of());
    }

    @Nested
    @DisplayName("names")
    class Names {

        @Test
        void an_unknown_parameter_should_be_unknown_parameter() {
            assertThat(pull("storedAfterseq=0")).contains(ProblemReason.UNKNOWN_PARAMETER);
            assertThat(pull("storedAfterSeq=0&foo=1")).contains(ProblemReason.UNKNOWN_PARAMETER);
            assertThat(search(COURT + DAYS + "&StoredAfterSeq=1")).contains(ProblemReason.UNKNOWN_PARAMETER);
            assertThat(ShareParameters.check(ApiRoute.GET_SHARE, "x=1", Map.of("shareId", SHARE_ID)))
                    .contains(ProblemReason.UNKNOWN_PARAMETER);
        }

        @Test
        void a_name_with_a_malformed_escape_should_be_unknown_parameter() {
            assertThat(pull("storedAfterSeq=0&%zz=1")).contains(ProblemReason.UNKNOWN_PARAMETER);
        }

        @Test
        void an_encoded_name_should_be_read_decoded() {
            assertThat(pull("stored%41fterSeq=0")).isEmpty();
        }

        @Test
        void a_repeated_parameter_should_be_repeated_parameter() {
            assertThat(pull("storedAfterSeq=0&limit=1&limit=2")).contains(ProblemReason.REPEATED_PARAMETER);
            assertThat(search(COURT + DAYS + "&" + COURT)).contains(ProblemReason.REPEATED_PARAMETER);
        }

        @Test
        void empty_pairs_should_be_skipped() {
            assertThat(pull("storedAfterSeq=0&&limit=5&")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"sharedDayFrom=2026-10-01", "sharedDayTo=2026-10-01",
            "sharedFrom=2026-10-01T00:00:00Z", "sharedTo=2026-10-01T00:00:00Z", "latestOnly=true", "cursor=abc"})
        void pull_with_a_search_parameter_should_be_conflicting_parameters(final String parameter) {
            assertThat(pull("storedAfterSeq=0&" + parameter)).contains(ProblemReason.CONFLICTING_PARAMETERS);
        }

        @Test
        void a_time_parameter_on_pull_should_be_conflicting_parameters() {
            assertThat(pull("storedAfterSeq=0&sharedTo=2026-10-01T00:00:00Z"))
                    .contains(ProblemReason.CONFLICTING_PARAMETERS);
        }

        @Test
        void a_day_parameter_with_a_time_parameter_should_be_conflicting_parameters() {
            assertThat(search(COURT + DAYS + TIMES)).contains(ProblemReason.CONFLICTING_PARAMETERS);
            assertThat(search(COURT + "&sharedDayFrom=2026-10-01&sharedTo=2026-10-03T17:00:00Z"))
                    .contains(ProblemReason.CONFLICTING_PARAMETERS);
        }

        @Test
        void neither_mode_complete_should_be_missing_parameter() {
            assertThat(search(null)).contains(ProblemReason.MISSING_PARAMETER);
            assertThat(search("")).contains(ProblemReason.MISSING_PARAMETER);
            assertThat(search(DAYS.substring(1))).contains(ProblemReason.MISSING_PARAMETER);
            assertThat(search(COURT)).contains(ProblemReason.MISSING_PARAMETER);
        }

        @ParameterizedTest
        @ValueSource(strings = {"&sharedDayFrom=2026-10-01", "&sharedDayTo=2026-10-01",
            "&sharedFrom=2026-10-01T00:00:00Z", "&sharedTo=2026-10-01T00:00:00Z"})
        void an_incomplete_form_should_be_missing_parameter(final String half) {
            assertThat(search(COURT + half)).contains(ProblemReason.MISSING_PARAMETER);
        }

        @Test
        void search_should_accept_the_day_form_or_the_time_form() {
            assertThat(search(COURT + DAYS)).isEmpty();
            assertThat(search(COURT + TIMES)).isEmpty();
            assertThat(search(COURT + DAYS + "&dayYouthSeen=false&latestOnly=true&limit=500&cursor=djF8")).isEmpty();
        }

        @Test
        void pull_should_accept_its_own_parameters() {
            assertThat(pull("storedAfterSeq=0")).isEmpty();
            assertThat(pull("storedAfterSeq=9223372036854775807&limit=1&dayYouthSeen=notFalse&" + COURT)).isEmpty();
        }

        @Test
        void the_other_routes_should_take_no_query() {
            assertThat(ShareParameters.check(ApiRoute.GET_SHARE_PAYLOAD, null, Map.of("shareId", SHARE_ID)))
                    .isEmpty();
            assertThat(ShareParameters.check(ApiRoute.LIST_HEARING_DAY_SHARES, "",
                    Map.of("hearingId", SHARE_ID, "hearingDay", "2026-10-02"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("values")
    class Values {

        /** One row per invalid reason of contracts/read-api.md §6 that a value alone gives. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
            storedAfterSeq=-1 | INVALID_STORED_AFTER_SEQ
            storedAfterSeq=1.5 | INVALID_STORED_AFTER_SEQ
            storedAfterSeq= | INVALID_STORED_AFTER_SEQ
            storedAfterSeq=9223372036854775808 | INVALID_STORED_AFTER_SEQ
            storedAfterSeq=+1 | INVALID_STORED_AFTER_SEQ
            storedAfterSeq=0&limit=0 | LIMIT_OUT_OF_RANGE
            storedAfterSeq=0&limit=501 | LIMIT_OUT_OF_RANGE
            storedAfterSeq=0&limit= | LIMIT_OUT_OF_RANGE
            storedAfterSeq=0&limit=ten | LIMIT_OUT_OF_RANGE
            storedAfterSeq=0&dayYouthSeen=NOTFALSE | INVALID_DAY_YOUTH_SEEN
            storedAfterSeq=0&dayYouthSeen= | INVALID_DAY_YOUTH_SEEN
            storedAfterSeq=0&courtCentreId=1-1-1-1-1 | INVALID_COURT_CENTRE_ID
            storedAfterSeq=0&courtCentreId=%7B2b3c4d5e-0000-4000-8000-000000000002%7D | INVALID_COURT_CENTRE_ID
            storedAfterSeq=%zz | INVALID_STORED_AFTER_SEQ
            storedAfterSeq | INVALID_STORED_AFTER_SEQ
            """)
        void a_pull_value_should_be_refused_with_its_own_reason(final String query, final ProblemReason reason) {
            assertThat(pull(query)).contains(reason);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
            courtCentreId=nope&sharedDayFrom=2026-10-01&sharedDayTo=2026-10-03 | INVALID_COURT_CENTRE_ID
            courtCentreId=%zz&sharedDayFrom=2026-10-01&sharedDayTo=2026-10-03 | INVALID_COURT_CENTRE_ID
            courtCentreId=2b3c4d5e-0000-4000-8000-000000000002&sharedDayFrom=2026-10-1&sharedDayTo=2026-10-03 | INVALID_SHARED_DAY
            courtCentreId=2b3c4d5e-0000-4000-8000-000000000002&sharedDayFrom=2026-10-01&sharedDayTo=2026-02-30 | INVALID_SHARED_DAY
            courtCentreId=2b3c4d5e-0000-4000-8000-000000000002&sharedFrom=2026-10-01&sharedTo=2026-10-03T00:00:00Z | INVALID_SHARED_FROM
            courtCentreId=2b3c4d5e-0000-4000-8000-000000000002&sharedFrom=2026-10-01T00:00:00Z&sharedTo=2026-10-03T25:00:00Z | INVALID_SHARED_TO
            courtCentreId=2b3c4d5e-0000-4000-8000-000000000002&sharedDayFrom=2026-10-01&sharedDayTo=2026-10-03&latestOnly=yes | INVALID_LATEST_ONLY
            courtCentreId=2b3c4d5e-0000-4000-8000-000000000002&sharedDayFrom=2026-10-01&sharedDayTo=2026-10-03&dayYouthSeen=maybe | INVALID_DAY_YOUTH_SEEN
            courtCentreId=2b3c4d5e-0000-4000-8000-000000000002&sharedDayFrom=2026-10-01&sharedDayTo=2026-10-03&limit=0 | LIMIT_OUT_OF_RANGE
            """)
        void a_search_value_should_be_refused_with_its_own_reason(final String query, final ProblemReason reason) {
            assertThat(search(query)).contains(reason);
        }

        @Test
        void day_youth_seen_false_should_be_refused_on_pull_and_accepted_on_search() {
            assertThat(pull("storedAfterSeq=0&dayYouthSeen=false")).contains(ProblemReason.INVALID_DAY_YOUTH_SEEN);
            assertThat(pull("storedAfterSeq=0&dayYouthSeen=true")).isEmpty();
            assertThat(search(COURT + DAYS + "&dayYouthSeen=false")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"2026-10-03T18:00:00+01:00", "2026-10-03T18:00:00.1234567Z", "2026-10-03T18:00:00",
            "2026-10-03t18:00:00Z", "2026-10-03T18:00Z", "2026-10-03T18:00:00.Z"})
        void an_instant_with_an_offset_or_seven_fraction_digits_should_be_invalid_shared_from_or_to(
                final String instant) {
            final String encoded = instant.replace("+", "%2B");
            assertThat(search(COURT + "&sharedFrom=" + encoded + "&sharedTo=2026-10-04T00:00:00Z"))
                    .contains(ProblemReason.INVALID_SHARED_FROM);
            assertThat(search(COURT + "&sharedFrom=2026-10-01T00:00:00Z&sharedTo=" + encoded))
                    .contains(ProblemReason.INVALID_SHARED_TO);
        }

        @ParameterizedTest
        @ValueSource(strings = {"2026-10-03T18:00:00Z", "2026-10-03T18:00:00.1Z", "2026-10-03T18:00:00.123456Z"})
        void an_instant_with_z_and_up_to_six_fraction_digits_should_be_accepted(final String instant) {
            assertThat(search(COURT + "&sharedFrom=" + instant + "&sharedTo=2026-10-04T00:00:00Z")).isEmpty();
        }

        @Test
        void a_non_canonical_uuid_should_be_invalid_even_though_uuid_from_string_takes_it() {
            assertThat(ShareParameters.check(ApiRoute.GET_SHARE, null, Map.of("shareId", "1-1-1-1-1")))
                    .contains(ProblemReason.INVALID_SHARE_ID);
            assertThat(ShareParameters.check(ApiRoute.GET_SHARE_PAYLOAD, null, Map.of("shareId", "1-1-1-1-1")))
                    .contains(ProblemReason.INVALID_SHARE_ID);
            assertThat(ShareParameters.check(ApiRoute.LIST_HEARING_DAY_SHARES, null,
                    Map.of("hearingId", "1-1-1-1-1", "hearingDay", "2026-10-02")))
                    .contains(ProblemReason.INVALID_HEARING_ID);
            assertThat(pull("storedAfterSeq=0&courtCentreId=1-1-1-1-1")).contains(ProblemReason.INVALID_COURT_CENTRE_ID);
        }

        @ParameterizedTest
        @ValueSource(strings = {"2026-10-2", "2026-13-01", "20261002", "2026-02-30"})
        void a_bad_hearing_day_should_be_invalid_hearing_day(final String day) {
            assertThat(ShareParameters.check(ApiRoute.LIST_HEARING_DAY_SHARES, null,
                    Map.of("hearingId", SHARE_ID, "hearingDay", day))).contains(ProblemReason.INVALID_HEARING_DAY);
        }

        @ParameterizedTest
        @ValueSource(strings = {"yes", "on", "1", "TRUE", ""})
        void latest_only_yes_on_or_1_should_be_invalid_latest_only(final String value) {
            assertThat(search(COURT + DAYS + "&latestOnly=" + value)).contains(ProblemReason.INVALID_LATEST_ONLY);
        }

        @Test
        void the_path_should_be_checked_before_the_query() {
            assertThat(ShareParameters.check(ApiRoute.GET_SHARE, "x=1", Map.of("shareId", "nope")))
                    .contains(ProblemReason.INVALID_SHARE_ID);
        }
    }
}
