package uk.gov.hmcts.cp.resultsstore.support;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import org.springframework.mock.web.MockHttpServletResponse;

/** A response whose body cannot be written, as when the client has gone: every write throws. */
public final class BrokenPipeResponse extends MockHttpServletResponse {

    /** The message of the exception every write throws. */
    public static final String BROKEN_PIPE = "Broken pipe";

    @Override
    public ServletOutputStream getOutputStream() {
        return new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(final WriteListener writeListener) {
                // Synchronous only.
            }

            @Override
            public void write(final int value) throws IOException {
                throw new IOException(BROKEN_PIPE);
            }

            @Override
            public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                throw new IOException(BROKEN_PIPE);
            }
        };
    }
}
