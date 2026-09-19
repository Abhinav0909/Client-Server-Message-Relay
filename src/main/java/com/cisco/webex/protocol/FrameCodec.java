package com.cisco.webex.protocol;

import com.cisco.webex.config.TransportProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;

/** Reads and writes newline-delimited JSON frames, bounded by {@link TransportProperties#getMaxFrameBytes()}. */
@Component
public class FrameCodec {

    private final ObjectMapper mapper;
    private final int maxLineBytes;

    public FrameCodec(ObjectMapper mapper, TransportProperties transportProperties) {
        this.mapper = mapper;
        this.maxLineBytes = transportProperties.getMaxFrameBytes();
    }

    /** Reads one frame, or null when the peer closes cleanly. */
    public Frame decode(BufferedReader reader) throws IOException, ProtocolException {
        String line = readBoundedLine(reader);
        if (line == null) {
            return null;
        }
        if (line.isBlank()) {
            throw new ProtocolException("Empty frame line");
        }
        try {
            return mapper.readValue(line, Frame.class);
        } catch (Exception e) {
            throw new ProtocolException("Malformed JSON frame: " + e.getMessage(), e);
        }
    }

    /** Writes one frame as JSON followed by a newline. */
    public void encode(Writer writer, Frame frame) throws IOException {
        String json = mapper.writeValueAsString(frame);
        writer.write(json);
        writer.write("\n");
        writer.flush();
    }

    /** Reads one line while enforcing the configured max frame size. */
    private String readBoundedLine(BufferedReader reader) throws IOException, ProtocolException {
        StringBuilder sb = new StringBuilder();
        int c;
        int bytes = 0;
        boolean anyRead = false;

        while ((c = reader.read()) != -1) {
            anyRead = true;
            if (c == '\n') {
                return sb.toString();
            }
            if (c != '\r') {
                sb.append((char) c);
                bytes += utf8ByteLength((char) c);
                if (bytes > maxLineBytes) {
                    throw new ProtocolException("Frame exceeds max size of " + maxLineBytes + " bytes");
                }
            }
        }

        if (!anyRead) {
            return null;
        }
        if (sb.isEmpty()) {
            return null;
        }
        // Accept a final unterminated frame.
        return sb.toString();
    }

    /**
     * UTF-8 byte length contributed by one UTF-16 char. Property is named
     * and documented in bytes (max-frame-bytes), so it must be checked
     * against actual encoded size, not char count — a char count silently
     * under-counts any non-ASCII payload against the configured limit.
     * Surrogate halves are each counted as 3 bytes rather than the 4 bytes
     * their combined code point would encode to — an intentional
     * over-count (never lets an oversized frame through undetected), not
     * an exact code-point-aware count.
     */
    private static int utf8ByteLength(char c) {
        if (c <= 0x7F) {
            return 1;
        } else if (c <= 0x7FF) {
            return 2;
        }
        return 3;
    }
}
