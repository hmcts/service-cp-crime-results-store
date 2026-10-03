package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the payload form header")
class PayloadFormTest {

    /** The {@code Results-Store-Payload-Form} values of contracts/read-api.md §4.4. */
    @Test
    void every_header_value_should_come_from_the_contract() {
        assertThat(Arrays.stream(PayloadForm.values()).map(PayloadForm::headerValue))
                .containsExactly("working-copy", "arrived-text");
    }
}
