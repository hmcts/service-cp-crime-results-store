package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("whole-response deadline")
class DeadlineInputStreamTest {

    private static final long DEADLINE = 1_000L;

    private final AtomicLong now = new AtomicLong(0L);

    private InputStream body(final String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void reads_before_the_deadline_should_pass() throws IOException {
        try (DeadlineInputStream stream = new DeadlineInputStream(body("{}"), DEADLINE, now::get)) {
            assertThat(stream.read()).isEqualTo('{');
            now.set(DEADLINE);
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("}");
        }
    }

    @Test
    void single_byte_read_after_the_deadline_should_time_out() throws IOException {
        try (DeadlineInputStream stream = new DeadlineInputStream(body("{}"), DEADLINE, now::get)) {
            now.set(DEADLINE + 1);
            assertThatThrownBy(stream::read).isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void buffer_read_after_the_deadline_should_time_out() throws IOException {
        try (DeadlineInputStream stream = new DeadlineInputStream(body("{}"), DEADLINE, now::get)) {
            now.set(DEADLINE + 1);
            assertThatThrownBy(() -> stream.read(new byte[8], 0, 8)).isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void read_that_ends_past_the_deadline_should_time_out() throws IOException {
        // The read itself takes the clock past the deadline: a slow drip that blocks in each read.
        final InputStream slow = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(final byte[] buffer, final int offset, final int length) {
                now.set(DEADLINE + 1);
                return super.read(buffer, offset, length);
            }
        };
        try (DeadlineInputStream stream = new DeadlineInputStream(slow, DEADLINE, now::get)) {
            assertThatThrownBy(() -> stream.read(new byte[8], 0, 8)).isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void close_should_close_the_delegate() throws IOException {
        final AtomicBoolean closed = new AtomicBoolean();
        final InputStream delegate = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        new DeadlineInputStream(delegate, DEADLINE, now::get).close();
        assertThat(closed).isTrue();
    }
}
