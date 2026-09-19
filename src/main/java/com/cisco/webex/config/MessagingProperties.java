package com.cisco.webex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings bound from {@code relay.messaging.*} — business-rule bounds on the message itself. */
@ConfigurationProperties(prefix = "relay.messaging")
public class MessagingProperties {

    /** Maximum SEND payload in bytes. */
    private int maxPayloadBytes = 65536;

    /** Per-recipient bounded window of recently-seen msgIds, used for duplicate SEND detection. */
    private int dedupeWindowSize = 1000;

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public int getDedupeWindowSize() {
        return dedupeWindowSize;
    }

    public void setDedupeWindowSize(int dedupeWindowSize) {
        this.dedupeWindowSize = dedupeWindowSize;
    }
}
