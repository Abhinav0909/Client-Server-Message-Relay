package com.cisco.webex.protocol;

import com.cisco.webex.config.TransportProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameCodecTest {

    private FrameCodec codec(int maxFrameBytes) {
        TransportProperties properties = new TransportProperties();
        properties.setMaxFrameBytes(maxFrameBytes);
        return new FrameCodec(new ObjectMapper(), properties);
    }

    private BufferedReader readerFor(String content) {
        return new BufferedReader(new StringReader(content));
    }

    @Test
    void decodesAWellFormedFrame() throws Exception {
        FrameCodec codec = codec(70_000);
        BufferedReader reader = readerFor("{\"op\":\"REGISTER\",\"clientId\":\"alice\"}\n");

        Frame frame = codec.decode(reader);

        assertThat(frame.getOp()).isEqualTo(Operations.REGISTER);
        assertThat(frame.getClientId()).isEqualTo("alice");
    }

    @Test
    void returnsNullOnCleanEof() throws Exception {
        FrameCodec codec = codec(70_000);
        BufferedReader reader = readerFor("");

        assertThat(codec.decode(reader)).isNull();
    }

    @Test
    void acceptsAFinalUnterminatedFrame() throws Exception {
        FrameCodec codec = codec(70_000);
        BufferedReader reader = readerFor("{\"op\":\"ACK\",\"msgId\":\"m1\"}"); // no trailing newline

        Frame frame = codec.decode(reader);

        assertThat(frame.getOp()).isEqualTo(Operations.ACK);
    }

    @Test
    void rejectsABlankLine() {
        FrameCodec codec = codec(70_000);
        BufferedReader reader = readerFor("\n{\"op\":\"ACK\"}\n");

        assertThatThrownBy(() -> codec.decode(reader)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsMalformedJson() {
        FrameCodec codec = codec(70_000);
        BufferedReader reader = readerFor("{not valid json\n");

        assertThatThrownBy(() -> codec.decode(reader)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsAFrameExceedingMaxBytes() {
        FrameCodec codec = codec(10); // tiny bound
        BufferedReader reader = readerFor("{\"op\":\"REGISTER\",\"clientId\":\"alice\"}\n");

        assertThatThrownBy(() -> codec.decode(reader)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void encodesAFrameAsJsonFollowedByNewline() throws IOException {
        FrameCodec codec = codec(70_000);
        StringWriter writer = new StringWriter();
        Frame frame = Frame.of(Operations.REGISTER_OK);

        codec.encode(writer, frame);

        assertThat(writer.toString()).isEqualTo("{\"op\":\"REGISTER_OK\"}\n");
    }

    @Test
    void roundTripsThroughEncodeThenDecode() throws Exception {
        FrameCodec codec = codec(70_000);
        StringWriter writer = new StringWriter();
        Frame original = Frame.of(Operations.SEND);
        original.setMsgId("m1");
        original.setTo("bob");
        original.setPayload("hello");

        codec.encode(writer, original);
        Frame decoded = codec.decode(readerFor(writer.toString()));

        assertThat(decoded.getOp()).isEqualTo(Operations.SEND);
        assertThat(decoded.getMsgId()).isEqualTo("m1");
        assertThat(decoded.getTo()).isEqualTo("bob");
        assertThat(decoded.getPayload()).isEqualTo("hello");
    }
}
