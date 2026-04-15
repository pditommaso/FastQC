package uk.ac.babraham.FastQC.Sequence;

import java.io.IOException;
import java.io.InputStream;

/**
 * High-performance line reader that works directly on bytes, bypassing
 * BufferedReader and InputStreamReader. FASTQ data is ASCII-only, so
 * UTF-8 decoding is unnecessary overhead.
 *
 * This reader:
 * - Reads into a large reusable byte[] buffer (no per-read allocation)
 * - Scans for newlines in the byte array (cache-friendly linear scan)
 * - Creates String directly from bytes (one allocation per line)
 * - Can do uppercase conversion on the buffer before String creation
 */
public class ByteLineReader implements AutoCloseable {

    private final InputStream in;
    private final byte[] buf;
    private int pos;     // current read position in buffer
    private int limit;   // number of valid bytes in buffer
    private boolean eof;

    // Lookup table: uppercase ASCII in-place
    private static final byte[] UPPER = new byte[256];
    static {
        for (int i = 0; i < 256; i++) UPPER[i] = (byte) i;
        for (int i = 'a'; i <= 'z'; i++) UPPER[i] = (byte) (i - 32);
    }

    public ByteLineReader(InputStream in) {
        this(in, 256 * 1024); // 256KB buffer
    }

    public ByteLineReader(InputStream in, int bufferSize) {
        this.in = in;
        this.buf = new byte[bufferSize];
        this.pos = 0;
        this.limit = 0;
        this.eof = false;
    }

    /**
     * Read next line as a String. Returns null at EOF.
     */
    public String readLine() throws IOException {
        if (eof && pos >= limit) return null;

        // Fast path: scan for newline within current buffer
        int start = pos;
        while (true) {
            // Scan buffer for newline
            for (int i = pos; i < limit; i++) {
                if (buf[i] == '\n') {
                    int end = i;
                    if (end > start && buf[end - 1] == '\r') end--;
                    pos = i + 1;
                    return new String(buf, start, end - start);
                }
            }

            // No newline found — need more data
            if (eof) {
                // Return remaining data
                if (pos < limit) {
                    int end = limit;
                    pos = limit;
                    return new String(buf, start, end - start);
                }
                return null;
            }

            // Compact: move unprocessed data to start of buffer
            int remaining = limit - start;
            if (remaining > 0 && start > 0) {
                System.arraycopy(buf, start, buf, 0, remaining);
            }
            pos = remaining;
            start = 0;
            limit = remaining;

            // Fill rest of buffer
            int read = in.read(buf, limit, buf.length - limit);
            if (read == -1) {
                eof = true;
            } else {
                limit += read;
            }
        }
    }

    /**
     * Read next line, converting to uppercase in the buffer before
     * creating the String. Avoids a separate String.toUpperCase() call.
     */
    public String readLineUpperCase() throws IOException {
        if (eof && pos >= limit) return null;

        int start = pos;
        while (true) {
            for (int i = pos; i < limit; i++) {
                if (buf[i] == '\n') {
                    int end = i;
                    if (end > start && buf[end - 1] == '\r') end--;
                    // Uppercase in-place before creating String
                    for (int j = start; j < end; j++) {
                        buf[j] = UPPER[buf[j] & 0xFF];
                    }
                    pos = i + 1;
                    return new String(buf, start, end - start);
                }
            }

            if (eof) {
                if (pos < limit) {
                    int end = limit;
                    for (int j = start; j < end; j++) {
                        buf[j] = UPPER[buf[j] & 0xFF];
                    }
                    pos = limit;
                    return new String(buf, start, end - start);
                }
                return null;
            }

            int remaining = limit - start;
            if (remaining > 0 && start > 0) {
                System.arraycopy(buf, start, buf, 0, remaining);
            }
            pos = remaining;
            start = 0;
            limit = remaining;

            int read = in.read(buf, limit, buf.length - limit);
            if (read == -1) {
                eof = true;
            } else {
                limit += read;
            }
        }
    }

    /**
     * Skip next line without creating a String. Useful for the FASTQ
     * mid-line ('+') which is read but never used.
     * Returns true if a line was skipped, false at EOF.
     */
    public boolean skipLine() throws IOException {
        if (eof && pos >= limit) return false;

        while (true) {
            for (int i = pos; i < limit; i++) {
                if (buf[i] == '\n') {
                    pos = i + 1;
                    return true;
                }
            }

            if (eof) {
                pos = limit;
                return pos > 0;
            }

            // Buffer exhausted, refill
            pos = 0;
            limit = 0;
            int read = in.read(buf, 0, buf.length);
            if (read == -1) {
                eof = true;
                return false;
            }
            limit = read;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
