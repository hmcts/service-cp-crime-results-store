package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
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
}
