package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.openapi.model.PullPage;
import uk.gov.hmcts.cp.resultsstore.support.ShareViews;

/** Every instant the read API writes: UTC, {@code Z}, exactly six fraction digits (FR-005, research R16). */
@DisplayName("instant format")
class InstantFormatTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(InstantFormat.module()).build();

    @ParameterizedTest
    @CsvSource({
        "2026-10-03T09:15:00Z, 2026-10-03T09:15:00.000000Z",
        "2026-10-03T09:15:00.120Z, 2026-10-03T09:15:00.120000Z",
        "2026-10-03T09:15:00.123456789Z, 2026-10-03T09:15:00.123456Z",
        "1969-12-31T23:59:59.999999Z, 1969-12-31T23:59:59.999999Z"
    })
    void six_fraction_digits_should_always_be_written(final String instant, final String written) {
        assertThat(InstantFormat.format(Instant.parse(instant))).isEqualTo(written);
    }

    @Test
    void utc_with_z() {
        assertThat(InstantFormat.format(Instant.parse("2026-07-01T00:30:00+01:00")))
                .isEqualTo("2026-06-30T23:30:00.000000Z");
    }

    @Test
    void the_registered_serializer_should_write_every_model_instant_this_way() {
        final PullPage page = ShareResponseMapper.pullPage(new uk.gov.hmcts.cp.resultsstore.application.PullPage(
                List.of(ShareViews.complete()), 1L, false, Instant.parse("2026-10-03T18:00:04Z")));

        final JsonNode json = mapper.readTree(mapper.writeValueAsString(page));

        assertThat(json.get("visibleUpTo").asString()).isEqualTo("2026-10-03T18:00:04.000000Z");
        assertThat(json.get("items").get(0).get("sharedTime").asString()).isEqualTo("2026-10-02T16:41:07.512000Z");
        assertThat(json.get("items").get(0).get("storedAt").asString()).isEqualTo("2026-10-02T16:41:08.003117Z");
        assertThat(json.get("items").get(0).get("projectedAt").asString()).isEqualTo("2026-10-02T16:41:08.000000Z");
    }
}
