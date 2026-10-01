package uk.gov.hmcts.cp.resultsstore.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PublicEventEnvelopeTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void parse_should_read_the_identity_fields() {
        final PublicEventEnvelope envelope = PublicEventEnvelope.parse(mapper, """
                {"_metadata": {"name": "public.events.hearing.hearing-resulted"},
                 "hearing": {"id": "a1b2"}, "hearingDay": "2026-09-30",
                 "sharedTime": "2026-09-30T15:04:05.000Z"}""");

        assertThat(envelope).isEqualTo(
                new PublicEventEnvelope("a1b2", "2026-09-30", "2026-09-30T15:04:05.000Z"));
    }

    @Test
    void parse_should_leave_absent_or_non_string_fields_null() {
        final PublicEventEnvelope envelope = PublicEventEnvelope.parse(mapper,
                "{\"hearing\": {\"id\": 42}}");

        assertThat(envelope).isEqualTo(new PublicEventEnvelope(null, null, null));
    }

    @Test
    void parse_should_refuse_a_body_that_is_not_an_object() {
        assertThatThrownBy(() -> PublicEventEnvelope.parse(mapper, "[1, 2]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_should_refuse_a_body_that_is_not_json() {
        assertThatThrownBy(() -> PublicEventEnvelope.parse(mapper, "not json"))
                .isInstanceOf(JacksonException.class);
    }
}
