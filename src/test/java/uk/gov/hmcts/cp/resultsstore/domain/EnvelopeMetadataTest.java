package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.resultsstore.domain.EnvelopeMetadata.UnreadablePayloadException;

/** Payload assertions compare whole texts of synthetic fixtures only; no hearing content is used. */
@DisplayName("removing the envelope metadata")
class EnvelopeMetadataTest {

    @Test
    void the_top_level_metadata_member_should_be_removed() {
        assertThat(EnvelopeMetadata.strip(
                "{\"_metadata\": {\"name\": \"public.events.hearing.hearing-resulted\", \"id\": \"x\"},"
                        + " \"hearing\": {\"id\": \"h\"}, \"sharedTime\": \"t\"}"))
                .isEqualTo("{\"hearing\":{\"id\":\"h\"},\"sharedTime\":\"t\"}");
    }

    @Test
    void a_nested_metadata_key_should_be_left_alone() {
        assertThat(EnvelopeMetadata.strip("{\"hearing\":{\"_metadata\":{\"a\":1}},\"_metadata\":{}}"))
                .isEqualTo("{\"hearing\":{\"_metadata\":{\"a\":1}}}");
    }

    @Test
    void a_text_without_metadata_should_keep_its_content() {
        assertThat(EnvelopeMetadata.strip("{ \"b\" : [1, 2, {\"c\": null}], \"a\": true }"))
                .isEqualTo("{\"b\":[1,2,{\"c\":null}],\"a\":true}");
        assertThat(EnvelopeMetadata.strip("[{\"_metadata\":1}]")).isEqualTo("[{\"_metadata\":1}]");
    }

    @Test
    void key_order_and_exact_numbers_should_be_kept() {
        assertThat(EnvelopeMetadata.strip("{\"z\":1.50,\"_metadata\":{},\"a\":12345678901234567890,"
                + "\"m\":1E+2,\"n\":-0.000000000000000000001,\"i\":7}"))
                .isEqualTo("{\"z\":1.50,\"a\":12345678901234567890,\"m\":1E+2,\"n\":-1E-21,\"i\":7}");
    }

    @Test
    void a_u0000_escape_should_survive() {
        assertThat(EnvelopeMetadata.strip("{\"_metadata\":{},\"note\":\"a\\u0000b\"}"))
                .isEqualTo("{\"note\":\"a\\u0000b\"}");
    }

    @Test
    void non_ascii_text_should_be_written_as_utf_8_characters() {
        assertThat(EnvelopeMetadata.strip("{\"_metadata\":{},\"name\":\"Zoë £\"}"))
                .isEqualTo("{\"name\":\"Zoë £\"}");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"secret-marker-7d1 not json", "{\"secret-marker-7d1\": ", "{} {\"secret-marker-7d1\":1}",
        ""})
    void a_text_that_is_not_json_should_fail_without_echoing_it(final String text) {
        assertThatThrownBy(() -> EnvelopeMetadata.strip(text))
                .isInstanceOf(UnreadablePayloadException.class)
                .hasNoCause()
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("secret-marker-7d1")
                        .startsWith("the stored payload text is not JSON"));
    }
}
