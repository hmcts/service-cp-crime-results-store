package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ShareIdTest {

    private static final String HEARING_ID = "6f1f0c3e-2b7a-4c3e-9a51-2f7d1c0e8a11";

    @Test
    void namespace_should_be_the_fixed_constant() {
        assertThat(ShareId.NAMESPACE).isEqualTo(UUID.fromString("3f6c2a4e-8d1b-4f0a-9c57-1e2b7d9a4c60"));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-02T14:19:50.706Z,  3ca3dfde-49ec-529d-abd7-76818b64f17c",
        "2026-10-02T14:19:50.7060Z, 7c8a5ca0-4a02-54b9-a230-bd29a009c962"
    })
    void from_golden_vector_should_give_the_known_id(final String sharedTime, final String expected) {
        assertThat(ShareId.from(HEARING_ID, "2026-10-02", sharedTime)).isEqualTo(UUID.fromString(expected));
    }

    @Test
    void from_two_spellings_of_one_instant_should_give_two_ids() {
        assertThat(ShareId.from(HEARING_ID, "2026-10-02", "2026-10-02T14:19:50.706Z"))
                .isNotEqualTo(ShareId.from(HEARING_ID, "2026-10-02", "2026-10-02T14:19:50.7060Z"));
    }

    @Test
    void from_should_give_a_version_5_rfc_4122_uuid() {
        final UUID shareId = ShareId.from(HEARING_ID, "2026-10-02", "2026-10-02T14:19:50.706Z");

        assertThat(shareId.version()).isEqualTo(5);
        assertThat(shareId.variant()).isEqualTo(2);
    }

    @Test
    void from_the_same_strings_should_always_give_the_same_id() {
        assertThat(ShareId.from(HEARING_ID, "2026-10-02", "2026-10-02T14:19:50.706Z"))
                .isEqualTo(ShareId.from(HEARING_ID, "2026-10-02", "2026-10-02T14:19:50.706Z"));
    }
}
