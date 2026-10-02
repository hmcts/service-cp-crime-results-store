package uk.gov.hmcts.cp.resultsstore.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.NotShare;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Reading;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Share;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.ShareId;
import uk.gov.hmcts.cp.resultsstore.domain.ShareIdentity;

class ShareIdentityParserTest {

    private static final String HEARING_ID = "6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11";

    private static final String HEARING_DAY = "2026-10-02";

    private static final String SHARED_TIME = "2026-10-02T14:19:50.706Z";

    private final ShareIdentityParser parser = new ShareIdentityParser(JsonMapper.builder().build());

    @Nested
    @DisplayName("a share")
    class AShare {

        @Test
        void read_of_a_valid_body_should_give_the_share_with_its_identity() {
            final Reading reading = parser.read(body("\"" + HEARING_ID + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + SHARED_TIME + "\""));

            assertThat(reading).isInstanceOfSatisfying(Share.class, share -> {
                assertThat(share.identity()).isEqualTo(new ShareIdentity(UUID.fromString(HEARING_ID),
                        LocalDate.parse(HEARING_DAY), Instant.parse(SHARED_TIME), HEARING_ID, HEARING_DAY,
                        SHARED_TIME));
                assertThat(share.body().path("isReshare").booleanValue()).isFalse();
            });
        }

        @Test
        void read_should_keep_the_three_identity_strings_exactly_as_sent() {
            final String upperCaseId = HEARING_ID.toUpperCase(Locale.ROOT);
            final String fourDigits = "2026-10-02T15:19:50.7060+01:00";

            final Reading reading = parser.read(body("\"" + upperCaseId + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + fourDigits + "\""));

            assertThat(reading).isInstanceOfSatisfying(Share.class, share -> {
                assertThat(share.identity().rawHearingId()).isEqualTo(upperCaseId);
                assertThat(share.identity().rawSharedTime()).isEqualTo(fourDigits);
                assertThat(share.identity().sharedAt()).isEqualTo(Instant.parse(SHARED_TIME));
                assertThat(share.identity().shareId())
                        .isEqualTo(ShareId.from(upperCaseId, HEARING_DAY, fourDigits));
            });
        }
        @ParameterizedTest(name = "hearingDay = {0}")
        @ValueSource(strings = {"0000-01-01", "0001-01-01", "9999-12-31"})
        void read_with_a_hearing_day_at_either_end_of_the_four_digit_years_should_give_the_share(
                final String value) {
            final Reading reading = parser.read(body("\"" + HEARING_ID + "\"", "\"" + value + "\"",
                    "\"" + SHARED_TIME + "\""));

            assertThat(reading).isInstanceOfSatisfying(Share.class,
                    share -> assertThat(share.identity().hearingDay()).isEqualTo(LocalDate.parse(value)));
        }

        @ParameterizedTest(name = "sharedTime = {0}")
        @CsvSource(delimiter = '|', value = {
            "0000-01-01T00:00:00+18:00           | -0001-12-31T06:00:00Z",
            "0000-12-31T23:59:59Z                | 0000-12-31T23:59:59Z",
            "0001-01-01T00:00:00Z                | 0001-01-01T00:00:00Z",
            "0001-01-01T00:30:00+01:00           | 0000-12-31T23:30:00Z",
            "0001-01-01T00:30:00-01:00           | 0001-01-01T01:30:00Z",
            "9999-12-31T23:30:00-01:00           | +10000-01-01T00:30:00Z",
            "9999-12-31T23:59:59.999999-18:00    | +10000-01-01T17:59:59.999999Z",
            "9999-12-31T23:59:59.999999999Z      | 9999-12-31T23:59:59.999999Z",
            "9999-12-31T23:59:59.999999999+01:00 | 9999-12-31T22:59:59.999999Z"
        })
        void read_with_a_shared_time_at_either_end_of_the_four_digit_years_should_give_the_share(
                final String value, final String instant) {
            final Reading reading = parser.read(body("\"" + HEARING_ID + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + value + "\""));

            assertThat(reading).isInstanceOfSatisfying(Share.class,
                    share -> assertThat(share.identity().sharedAt()).isEqualTo(Instant.parse(instant)));
        }

        @ParameterizedTest(name = "sharedTime = {0}")
        @CsvSource(delimiter = '|', value = {
            "2026-10-02T14:19:50.1234567Z      | 2026-10-02T14:19:50.123456Z",
            "2026-10-02T14:19:50.12345678Z     | 2026-10-02T14:19:50.123456Z",
            "2026-10-02T14:19:50.123456789Z    | 2026-10-02T14:19:50.123456Z",
            "2026-10-02T15:19:50.999999999+01:00 | 2026-10-02T14:19:50.999999Z"
        })
        void read_with_a_shared_time_finer_than_microseconds_should_truncate_it_to_the_microsecond(
                final String value, final String instant) {
            final Reading reading = parser.read(body("\"" + HEARING_ID + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + value + "\""));

            assertThat(reading).isInstanceOfSatisfying(Share.class, share -> {
                assertThat(share.identity().sharedAt()).isEqualTo(Instant.parse(instant));
                assertThat(share.identity().rawSharedTime()).isEqualTo(value);
                assertThat(share.identity().shareId()).isEqualTo(ShareId.from(HEARING_ID, HEARING_DAY, value));
            });
        }

        @Test
        void read_of_two_shared_times_differing_only_past_the_sixth_digit_should_give_one_shared_at() {
            final String sevenDigits = "2026-10-02T14:19:50.1234561Z";
            final String nineDigits = "2026-10-02T14:19:50.123456999Z";

            final Reading first = parser.read(body("\"" + HEARING_ID + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + sevenDigits + "\""));
            final Reading second = parser.read(body("\"" + HEARING_ID + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + nineDigits + "\""));

            assertThat(List.of(first, second)).allSatisfy(reading -> assertThat(reading)
                    .isInstanceOfSatisfying(Share.class, share -> assertThat(share.identity().sharedAt())
                            .isEqualTo(Instant.parse("2026-10-02T14:19:50.123456Z"))));
            assertThat(((Share) first).identity().shareId()).as("share ids hash the strings as sent")
                    .isNotEqualTo(((Share) second).identity().shareId());
        }

        @ParameterizedTest(name = "wrapping {index}")
        @ValueSource(strings = {" %s \n", "\n%s", "%s\r\n"})
        void read_of_a_valid_body_with_surrounding_whitespace_should_give_the_same_share(final String wrapping) {
            final String valid = body("\"" + HEARING_ID + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + SHARED_TIME + "\"");

            final Reading reading = parser.read(wrapping.formatted(valid));

            assertThat(reading).isInstanceOfSatisfying(Share.class, share -> assertThat(share.identity())
                    .isEqualTo(new ShareIdentity(UUID.fromString(HEARING_ID), LocalDate.parse(HEARING_DAY),
                            Instant.parse(SHARED_TIME), HEARING_ID, HEARING_DAY, SHARED_TIME)));
        }

        @Test
        void read_of_an_escaped_nul_should_give_the_share() {
            final String sixCharacterEscape = "\\" + "u0000";
            final String text = body("\"" + HEARING_ID + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + SHARED_TIME + "\"").replace("\"isReshare\"", "\"note\": \"a" + sixCharacterEscape
                    + "b\", \"isReshare\"");

            final Reading reading = parser.read(text);

            assertThat(text).doesNotContain(String.valueOf((char) 0)).contains(sixCharacterEscape);
            assertThat(reading).isInstanceOfSatisfying(Share.class,
                    share -> assertThat(share.body().path("note").stringValue()).isEqualTo("a" + (char) 0 + "b"));
        }
    }

    @Nested
    @DisplayName("an unreadable body")
    class Unreadable {

        @Test
        void read_of_no_text_should_be_not_a_text_message() {
            assertThat(parser.read(null)).isEqualTo(notShare(NonShareReason.NOT_TEXT_MESSAGE));
        }

        @Test
        void read_of_a_raw_nul_character_should_be_nul_character() {
            final String text = body("\"" + HEARING_ID + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + SHARED_TIME + "\"").replace("\"isReshare\"", "\"is\u0000Reshare\"");

            assertThat(parser.read(text)).isEqualTo(notShare(NonShareReason.NUL_CHARACTER));
        }

        @ParameterizedTest
        @ValueSource(strings = {"not json", "", "   ", "{\"hearing\": ", "{} {}", "{\"a\": 1} trailing", "{'a': 1}"})
        void read_of_text_that_is_not_one_json_value_should_be_not_json(final String text) {
            assertThat(parser.read(text)).isEqualTo(notShare(NonShareReason.NOT_JSON));
        }

        @ParameterizedTest
        @ValueSource(strings = {"[1, 2]", "\"just a string\"", "42", "null", "true"})
        void read_of_json_that_is_not_an_object_should_be_not_object(final String text) {
            assertThat(parser.read(text)).isEqualTo(notShare(NonShareReason.NOT_OBJECT));
        }
    }

    @Nested
    @DisplayName("a body without its identity")
    class NoIdentity {

        @ParameterizedTest(name = "hearing.id = {0}")
        @ValueSource(strings = {"ABSENT", "null", "42", "{}", "true"})
        void read_without_a_string_hearing_id_should_be_missing_hearing_id(final String value) {
            final Reading reading = parser.read(body(value, "\"" + HEARING_DAY + "\"", "\"" + SHARED_TIME + "\""));

            assertThat(reading).isEqualTo(new NotShare(NonShareReason.MISSING_HEARING_ID, null,
                    LocalDate.parse(HEARING_DAY), Instant.parse(SHARED_TIME)));
        }

        @ParameterizedTest(name = "hearing.id = {0}")
        @ValueSource(strings = {"a1b2", "1-1-1-1-1", "6f1f0c3e2b7a4c3e9a512f7d1c0e8a11", ""})
        void read_with_a_hearing_id_that_is_not_a_canonical_uuid_should_be_invalid_hearing_id(final String value) {
            final Reading reading = parser.read(body("\"" + value + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + SHARED_TIME + "\""));

            assertThat(reading).isEqualTo(new NotShare(NonShareReason.INVALID_HEARING_ID, null,
                    LocalDate.parse(HEARING_DAY), Instant.parse(SHARED_TIME)));
        }

        @ParameterizedTest(name = "hearingDay = {0}")
        @ValueSource(strings = {"ABSENT", "null", "20261002"})
        void read_without_a_string_hearing_day_should_be_missing_hearing_day(final String value) {
            final Reading reading = parser.read(body("\"" + HEARING_ID + "\"", value, "\"" + SHARED_TIME + "\""));

            assertThat(reading).isEqualTo(new NotShare(NonShareReason.MISSING_HEARING_DAY,
                    UUID.fromString(HEARING_ID), null, Instant.parse(SHARED_TIME)));
        }

        @ParameterizedTest(name = "hearingDay = {0}")
        @ValueSource(strings = {"2026-02-30", "02/10/2026", "2026-10-2", "2026-10-02T00:00:00Z", "",
            "+999999999-01-01", "-0001-01-01", "+2026-10-02", "+10000-01-01", "\uFF12026-10-02"})
        void read_with_a_hearing_day_that_is_not_an_iso_date_should_be_invalid_hearing_day(final String value) {
            final Reading reading = parser.read(body("\"" + HEARING_ID + "\"", "\"" + value + "\"",
                    "\"" + SHARED_TIME + "\""));

            assertThat(reading).isEqualTo(new NotShare(NonShareReason.INVALID_HEARING_DAY,
                    UUID.fromString(HEARING_ID), null, Instant.parse(SHARED_TIME)));
        }

        @ParameterizedTest(name = "sharedTime = {0}")
        @ValueSource(strings = {"ABSENT", "null", "1759414790706"})
        void read_without_a_string_shared_time_should_be_missing_shared_time(final String value) {
            final Reading reading = parser.read(body("\"" + HEARING_ID + "\"", "\"" + HEARING_DAY + "\"", value));

            assertThat(reading).isEqualTo(new NotShare(NonShareReason.MISSING_SHARED_TIME,
                    UUID.fromString(HEARING_ID), LocalDate.parse(HEARING_DAY), null));
        }

        @ParameterizedTest(name = "sharedTime = {0}")
        @ValueSource(strings = {"2026-10-02T14:19:50.706", "2026-10-02", "yesterday", "2026-10-02T25:19:50Z", "",
            "+999999999-01-01T00:00:00Z", "-0001-01-01T00:00:00Z", "+2026-10-02T14:19:50.706Z",
            "+10000-01-01T00:00:00Z"})
        void read_with_a_shared_time_that_is_not_a_date_time_with_offset_should_be_invalid_shared_time(
                final String value) {
            final Reading reading = parser.read(body("\"" + HEARING_ID + "\"", "\"" + HEARING_DAY + "\"",
                    "\"" + value + "\""));

            assertThat(reading).isEqualTo(new NotShare(NonShareReason.INVALID_SHARED_TIME,
                    UUID.fromString(HEARING_ID), LocalDate.parse(HEARING_DAY), null));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "all three missing                  | ABSENT   | ABSENT       | ABSENT  | MISSING_HEARING_ID",
            "id invalid, day missing            | '\"x\"'  | ABSENT       | ABSENT  | INVALID_HEARING_ID",
            "day invalid, time missing          | ID       | '\"2026\"'   | ABSENT  | INVALID_HEARING_DAY",
            "day missing, time invalid          | ID       | ABSENT       | '\"x\"' | MISSING_HEARING_DAY"
        })
        void read_with_several_problems_should_name_the_first_in_identity_order(final String name,
                final String hearingId, final String hearingDay, final String sharedTime,
                final NonShareReason expected) {
            final String id = "ID".equals(hearingId) ? "\"" + HEARING_ID + "\"" : hearingId;

            final Reading reading = parser.read(body(id, hearingDay, sharedTime));

            assertThat(reading).isInstanceOfSatisfying(NotShare.class,
                    notShare -> assertThat(notShare.reason()).isEqualTo(expected));
        }

        @Test
        void read_with_hearing_that_is_not_an_object_should_be_missing_hearing_id() {
            final Reading reading = parser.read("{\"hearing\": \"" + HEARING_ID + "\", \"hearingDay\": \""
                    + HEARING_DAY + "\", \"sharedTime\": \"" + SHARED_TIME + "\"}");

            assertThat(reading).isInstanceOfSatisfying(NotShare.class,
                    notShare -> assertThat(notShare.reason()).isEqualTo(NonShareReason.MISSING_HEARING_ID));
        }
    }

    private static NotShare notShare(final NonShareReason reason) {
        return new NotShare(reason, null, null, null);
    }

    /**
     * A real-shaped envelope. Each identity value is raw JSON, or {@code ABSENT} to leave the key out.
     */
    private static String body(final String hearingId, final String hearingDay, final String sharedTime) {
        return "{\"_metadata\": {\"name\": \"public.events.hearing.hearing-resulted\"}, "
                + "\"hearing\": {\"courtCentre\": {\"id\": \"9d2e4f6a-1b3c-4d5e-8f70-a1b2c3d4e5f6\"}"
                + field("id", hearingId) + "}"
                + field("hearingDay", hearingDay)
                + field("sharedTime", sharedTime)
                + ", \"isReshare\": false}";
    }

    private static String field(final String name, final String rawJson) {
        return "ABSENT".equals(rawJson) ? "" : ", \"" + name + "\": " + rawJson;
    }
}
