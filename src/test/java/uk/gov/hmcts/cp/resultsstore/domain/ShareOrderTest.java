package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("share order")
class ShareOrderTest {

    @ParameterizedTest
    @CsvSource({
        "false, IN_ORDER, in_order",
        "true, OUT_OF_ORDER, out_of_order"
    })
    void store_result_should_name_its_order_and_tag(final boolean outOfOrder, final ShareOrder order,
            final String tag) {
        assertThat(ShareOrder.from(outOfOrder)).isEqualTo(order);
        assertThat(order.tag()).isEqualTo(tag);
    }
}
