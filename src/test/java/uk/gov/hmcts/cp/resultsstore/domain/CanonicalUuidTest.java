package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CanonicalUuidTest {

    @ParameterizedTest
    @ValueSource(strings = {"6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11", "6F1F0C3E-2B7A-4C3E-9A51-2F7D1C0E8A11"})
    void parse_of_the_canonical_form_should_give_the_uuid(final String text) {
        assertThat(CanonicalUuid.parse(text)).contains(UUID.fromString(text));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1-1-1-1-1", "a1b2", "6f1f0c3e2b7a4c3e9a512f7d1c0e8a11",
        "6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a1", "6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11 ",
        "{6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11}", "6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a1g"})
    void parse_of_anything_else_should_give_nothing(final String text) {
        assertThat(CanonicalUuid.parse(text)).isEmpty();
    }
}
