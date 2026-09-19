package com.cisco.webex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings bound from {@code relay.broker.*} — the async hand-off queue between send() and delivery. */
@ConfigurationProperties(prefix = "relay.broker")
public class BrokerProperties {

    /** Maximum in-flight (published, not yet delivered+committed) messages across all recipients. */
    private int maxQueueSize = 10_000;

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public void setMaxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
    }
}
