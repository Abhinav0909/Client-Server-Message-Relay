package com.cisco.webex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings bound from {@code relay.storage.*} — mailbox-level bounds. */
@ConfigurationProperties(prefix = "relay.storage")
public class StorageProperties {

    /** Maximum total rows (QUEUED + UNACKED) per recipient mailbox. */
    private int maxMailboxSize = 100;

    public int getMaxMailboxSize() {
        return maxMailboxSize;
    }

    public void setMaxMailboxSize(int maxMailboxSize) {
        this.maxMailboxSize = maxMailboxSize;
    }
}
