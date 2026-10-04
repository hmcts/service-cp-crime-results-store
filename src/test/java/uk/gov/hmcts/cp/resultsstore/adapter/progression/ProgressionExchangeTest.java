package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;

@DisplayName("progression exchange")
class ProgressionExchangeTest {

    private static final URI TARGET = URI.create("http://progression.invalid/applications/1");

    private static final long DEADLINE = 1_000L;

    @Test
    void request_built_elsewhere_should_have_no_exchange() {
        final MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, TARGET);

        assertThatThrownBy(() -> ProgressionExchange.exchangeOf(request)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exchange_should_be_found_in_the_request_attributes() {
        final ProgressionExchange exchange = new ProgressionExchange(DEADLINE, new HttpGet(TARGET));
        final MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, TARGET);
        request.getAttributes().put(ProgressionExchange.ATTRIBUTE, exchange);

        assertThat(ProgressionExchange.exchangeOf(request)).isSameAs(exchange);
    }

    @Test
    void abort_should_cancel_the_transport_request_without_counting_as_the_deadline() {
        final HttpGet request = new HttpGet(TARGET);
        final ProgressionExchange exchange = new ProgressionExchange(DEADLINE, request);

        exchange.abort();

        assertThat(request.isCancelled()).isTrue();
        assertThat(exchange.isExpired()).isFalse();
    }

    @Test
    void expiry_should_cancel_the_transport_request_and_be_recorded() {
        final HttpGet request = new HttpGet(TARGET);
        final ProgressionExchange exchange = new ProgressionExchange(DEADLINE, request);

        exchange.expire();

        assertThat(request.isCancelled()).isTrue();
        assertThat(exchange.isExpired()).isTrue();
        assertThat(exchange.getDeadlineNanos()).isEqualTo(DEADLINE);
    }
}
