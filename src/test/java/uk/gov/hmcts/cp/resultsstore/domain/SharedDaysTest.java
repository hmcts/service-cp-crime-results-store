package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SharedDaysTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "summer evening (BST),                   2026-06-30T23:30:00.000Z, 2026-07-01, 2026-06-30",
        "evening before the clocks go forward,  2026-03-28T23:30:00.000Z, 2026-03-28, 2026-03-28",
        "first BST evening,                      2026-03-29T23:30:00.000Z, 2026-03-30, 2026-03-29",
        "last BST evening,                       2026-10-24T23:30:00.000Z, 2026-10-25, 2026-10-24",
        "first GMT evening,                      2026-10-25T23:30:00.000Z, 2026-10-25, 2026-10-25",
        "a minute past midnight UTC,             2026-10-02T00:01:00.000Z, 2026-10-02, 2026-10-02"
    })
    void from_should_give_the_london_and_utc_days(final String name, final Instant sharedAt,
            final LocalDate london, final LocalDate utc) {
        assertThat(SharedDays.from(sharedAt)).isEqualTo(new SharedDays(london, utc));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "a BST day,               2026-10-03, 2026-10-03, 2026-10-02T23:00:00Z, 2026-10-03T23:00:00Z",
        "a GMT day,               2026-12-01, 2026-12-01, 2026-12-01T00:00:00Z, 2026-12-02T00:00:00Z",
        "BST into GMT,            2026-10-24, 2026-10-26, 2026-10-23T23:00:00Z, 2026-10-27T00:00:00Z",
        "a 31-day range,          2026-01-01, 2026-01-31, 2026-01-01T00:00:00Z, 2026-02-01T00:00:00Z"
    })
    void a_london_day_range_should_become_london_midnight_to_london_midnight(final String name,
            final LocalDate from, final LocalDate to, final Instant expectedFrom, final Instant expectedTo) {
        assertThat(SharedDays.londonDays(from, to)).isEqualTo(new SharedDays.InstantRange(expectedFrom, expectedTo));
    }

    @Test
    void a_range_over_the_spring_and_autumn_clock_changes_should_cover_23_and_25_hours() {
        final SharedDays.InstantRange spring = SharedDays.londonDays(LocalDate.parse("2026-03-29"),
                LocalDate.parse("2026-03-29"));
        final SharedDays.InstantRange autumn = SharedDays.londonDays(LocalDate.parse("2026-10-25"),
                LocalDate.parse("2026-10-25"));

        assertThat(Duration.between(spring.from(), spring.to())).isEqualTo(Duration.ofHours(23));
        assertThat(Duration.between(autumn.from(), autumn.to())).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void every_instant_of_the_range_should_fall_on_a_london_day_inside_it() {
        final SharedDays.InstantRange range = SharedDays.londonDays(LocalDate.parse("2026-10-25"),
                LocalDate.parse("2026-10-25"));

        assertThat(SharedDays.from(range.from()).london()).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(SharedDays.from(range.to().minusNanos(1000)).london()).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(SharedDays.from(range.to()).london()).isEqualTo(LocalDate.parse("2026-10-26"));
        assertThat(SharedDays.from(range.from().minusNanos(1000)).london()).isEqualTo(LocalDate.parse("2026-10-24"));
    }
}
