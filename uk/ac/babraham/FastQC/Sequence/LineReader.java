package uk.ac.babraham.FastQC.Sequence;

import java.io.IOException;

/**
 * Common interface for line-based FASTQ readers.
 * Implemented by ByteLineReader (stream-based) and MappedByteLineReader (mmap).
 */
public interface LineReader extends AutoCloseable {
    String readLine() throws IOException;
    String readLineUpperCase() throws IOException;
    boolean skipLine() throws IOException;
    void close() throws IOException;
}
