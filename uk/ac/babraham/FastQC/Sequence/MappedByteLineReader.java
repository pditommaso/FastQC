package uk.ac.babraham.FastQC.Sequence;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;

/**
 * Memory-mapped FASTQ line reader using Java 21+ Foreign Memory API.
 * Maps the entire file into virtual memory and processes it through
 * a local byte[] window for fast scanning.
 */
public class MappedByteLineReader implements LineReader {

    private final Arena arena;
    private final MemorySegment mapped;
    private final long fileSize;
    private long filePos;        // position in the mapped file

    // Local buffer window — copied from mmap for fast byte scanning
    private byte[] buf;
    private int bufPos;
    private int bufLimit;
    private static final int BUF_SIZE = 256 * 1024;

    private static final byte[] UPPER = new byte[256];
    static {
        for (int i = 0; i < 256; i++) UPPER[i] = (byte) i;
        for (int i = 'a'; i <= 'z'; i++) UPPER[i] = (byte) (i - 32);
    }

    public MappedByteLineReader(FileChannel channel) throws IOException {
        this.fileSize = channel.size();
        this.arena = Arena.ofShared();
        this.mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);
        this.filePos = 0;
        this.buf = new byte[BUF_SIZE];
        this.bufPos = 0;
        this.bufLimit = 0;
        fillBuffer();
    }

    private void fillBuffer() {
        long remaining = fileSize - filePos;
        if (remaining <= 0) {
            bufPos = 0;
            bufLimit = 0;
            return;
        }
        int toRead = (int) Math.min(remaining, BUF_SIZE);
        MemorySegment.copy(mapped, ValueLayout.JAVA_BYTE, filePos, buf, 0, toRead);
        filePos += toRead;
        bufPos = 0;
        bufLimit = toRead;
    }

    public String readLine() throws IOException {
        if (bufPos >= bufLimit && filePos >= fileSize) return null;

        // Fast path: find newline within current buffer
        for (int i = bufPos; i < bufLimit; i++) {
            if (buf[i] == '\n') {
                int start = bufPos;
                int end = i;
                if (end > start && buf[end - 1] == '\r') end--;
                bufPos = i + 1;
                return new String(buf, start, end - start);
            }
        }

        // Slow path: line spans buffer boundary — accumulate
        byte[] line = new byte[1024];
        int lineLen = 0;

        // Copy remaining bytes from current buffer
        int rem = bufLimit - bufPos;
        if (rem > 0) {
            if (rem > line.length) line = new byte[rem * 2];
            System.arraycopy(buf, bufPos, line, 0, rem);
            lineLen = rem;
        }

        fillBuffer();
        while (bufLimit > 0) {
            for (int i = bufPos; i < bufLimit; i++) {
                if (buf[i] == '\n') {
                    int chunkLen = i - bufPos;
                    if (lineLen + chunkLen > line.length) {
                        byte[] bigger = new byte[(lineLen + chunkLen) * 2];
                        System.arraycopy(line, 0, bigger, 0, lineLen);
                        line = bigger;
                    }
                    System.arraycopy(buf, bufPos, line, lineLen, chunkLen);
                    lineLen += chunkLen;
                    bufPos = i + 1;
                    if (lineLen > 0 && line[lineLen - 1] == '\r') lineLen--;
                    return new String(line, 0, lineLen);
                }
            }
            // Copy whole buffer chunk
            int chunkLen = bufLimit - bufPos;
            if (lineLen + chunkLen > line.length) {
                byte[] bigger = new byte[(lineLen + chunkLen) * 2];
                System.arraycopy(line, 0, bigger, 0, lineLen);
                line = bigger;
            }
            System.arraycopy(buf, bufPos, line, lineLen, chunkLen);
            lineLen += chunkLen;
            fillBuffer();
        }

        if (lineLen > 0) {
            if (line[lineLen - 1] == '\r') lineLen--;
            return new String(line, 0, lineLen);
        }
        return null;
    }

    public String readLineUpperCase() throws IOException {
        if (bufPos >= bufLimit && filePos >= fileSize) return null;

        // Fast path
        for (int i = bufPos; i < bufLimit; i++) {
            if (buf[i] == '\n') {
                int start = bufPos;
                int end = i;
                if (end > start && buf[end - 1] == '\r') end--;
                // Uppercase in-place
                for (int j = start; j < end; j++) {
                    buf[j] = UPPER[buf[j] & 0xFF];
                }
                bufPos = i + 1;
                return new String(buf, start, end - start);
            }
        }

        // Slow path: read line normally then uppercase
        String line = readLine();
        if (line == null) return null;
        // Uppercase via byte array
        byte[] bytes = line.getBytes();
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = UPPER[bytes[i] & 0xFF];
        }
        return new String(bytes);
    }

    public boolean skipLine() throws IOException {
        if (bufPos >= bufLimit && filePos >= fileSize) return false;

        while (true) {
            for (int i = bufPos; i < bufLimit; i++) {
                if (buf[i] == '\n') {
                    bufPos = i + 1;
                    return true;
                }
            }
            fillBuffer();
            if (bufLimit == 0) return true;
        }
    }

    public int getPercentComplete() {
        if (fileSize == 0) return 100;
        long consumed = filePos - (bufLimit - bufPos);
        return (int) ((consumed * 100) / fileSize);
    }

    @Override
    public void close() throws IOException {
        arena.close();
    }
}
