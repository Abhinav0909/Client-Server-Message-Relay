package com.cisco.webex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings bound from {@code relay.transport.*} — socket/connection-level bounds. */
@ConfigurationProperties(prefix = "relay.transport")
public class TransportProperties {

    /** Listening port; 0 lets the OS choose. */
    private int port = 5555;

    /** Maximum open client connections. */
    private int maxActiveConnections = 1000;

    /** Maximum frame size in bytes (protocol-level, distinct from message payload size). */
    private int maxFrameBytes = 70000;

    /** Per-connection outbound frame buffer size, before a slow reader is considered backpressured. */
    private int writeQueueCapacity = 256;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxActiveConnections() {
        return maxActiveConnections;
    }

    public void setMaxActiveConnections(int maxActiveConnections) {
        this.maxActiveConnections = maxActiveConnections;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public void setMaxFrameBytes(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    public int getWriteQueueCapacity() {
        return writeQueueCapacity;
    }

    public void setWriteQueueCapacity(int writeQueueCapacity) {
        this.writeQueueCapacity = writeQueueCapacity;
    }
}
