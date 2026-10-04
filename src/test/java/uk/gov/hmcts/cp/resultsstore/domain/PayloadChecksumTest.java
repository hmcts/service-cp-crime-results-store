package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PayloadChecksumTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "empty      | ''           | e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "ascii      | abc          | ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "multi-byte | '{\"a\":\"é\"}' | b3a092a6af48807fa9482b2ee140105575daa26d5b24b3c0e60a7e2dee6683b1",
        "astral     | £€𝄞          | cd808db39951a7098df122d3faac2351840b60a61cb7ac6005dd9b070c539227"
    })
    void sha256_hex_should_be_the_utf8_digest_in_lower_case_hex(final String name, final String text,
            final String expected) {
        assertThat(PayloadChecksum.sha256Hex(text)).isEqualTo(expected).matches("^[0-9a-f]{64}$");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "empty      | ''",
        "ascii      | abc",
        "multi-byte | '{\"a\":\"é\"}'",
        "astral     | £€𝄞"
    })
    void the_bytes_form_should_equal_the_text_form_for_utf_8(final String name, final String text) {
        assertThat(PayloadChecksum.sha256Hex(text.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PayloadChecksum.sha256Hex(text));
    }

    @Test
    void different_bytes_should_give_different_hashes() {
        assertThat(PayloadChecksum.sha256Hex(new byte[] {1, 2, 3}))
                .isNotEqualTo(PayloadChecksum.sha256Hex(new byte[] {1, 2, 4}))
                .matches("^[0-9a-f]{64}$");
    }
}
