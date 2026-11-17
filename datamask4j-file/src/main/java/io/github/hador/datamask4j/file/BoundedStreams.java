package io.github.hador.datamask4j.file;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Objects;

/** Non-owning wrappers. Never expose underlying exception messages to public callers. */
final class BoundedStreams {
    private BoundedStreams() {}

    static Reader reader(Reader delegate, long limit) {
        Objects.requireNonNull(delegate, "reader");
        return new Reader() {
            private long remaining = limit;

            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, buffer.length);
                if (length == 0) {
                    return 0;
                }
                if (remaining == 0) {
                    if (delegate.read() == -1) {
                        return -1;
                    }
                    throw new IOException("Input limit exceeded");
                }
                int count = delegate.read(buffer, offset, (int) Math.min(length, remaining));
                if (count > 0) {
                    remaining -= count;
                }
                return count;
            }

            @Override
            public void close() {
                // Caller owns the delegate.
            }
        };
    }

    static Writer writer(Writer delegate, long limit) {
        Objects.requireNonNull(delegate, "writer");
        return new Writer() {
            private long remaining = limit;

            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, buffer.length);
                if (length > remaining) {
                    throw new IOException("Output limit exceeded");
                }
                delegate.write(buffer, offset, length);
                remaining -= length;
            }

            @Override
            public void flush() throws IOException {
                delegate.flush();
            }

            @Override
            public void close() {
                // Caller owns the delegate.
            }
        };
    }
}
