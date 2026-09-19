package com.cisco.webex.domain.client;

import com.cisco.webex.domain.messaging.Message;

import java.time.Instant;

/**
 * One logical client identity, independent of any single TCP connection.
 * The same instance is reused across disconnect/reconnect cycles — see
 * {@link ClientDirectory#getOrCreate(String)}.
 *
 * Not a Spring bean; owned and constructed by {@link ClientDirectory}.
 */
public class Client {

    private final String clientId;
    private final MessageDedupeWindow dedupeWindow;

    private volatile ClientChannel channel;
    private volatile Instant lastSeenAt = Instant.now();

    public Client(String clientId, int dedupeCapacity) {
        this.clientId = clientId;
        this.dedupeWindow = new MessageDedupeWindow(dedupeCapacity);
    }

    /** Stable client-supplied identifier. */
    public String getClientId() {
        return clientId;
    }

    /** True while a live {@link ClientChannel} is attached. */
    public boolean isOnline() {
        return channel != null;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    /** Binds this client to a new channel, evicting/closing any previous one. Called on REGISTER. */
    public void attach(ClientChannel channel) {
        ClientChannel previous = this.channel;
        if (previous != null && previous != channel) {
            previous.close();
        }
        this.channel = channel;
        this.lastSeenAt = Instant.now();
    }

    /** Detaches the current channel. Called when the connection drops. Client identity is retained. */
    public void detach() {
        this.channel = null;
    }

    /**
     * Attempts to push message through the attached channel, if any. Keeps
     * {@link ClientChannel} fully encapsulated — callers never obtain the
     * channel directly, they ask this Client to deliver.
     *
     * @return false if offline, or if the channel could not accept it
     *         (caller must fall back to mailbox storage in either case).
     */
    public boolean tryDeliver(Message message) {
        ClientChannel current = this.channel;
        return current != null && current.deliver(message);
    }

    /** Bounded de-dupe check, keyed by (sender, sender-supplied msgId). */
    public boolean isDuplicate(String from, String msgId) {
        return dedupeWindow.isDuplicate(from, msgId);
    }

    /** Records (from, msgId) as seen, evicting the oldest entry once the dedupe window is full. */
    public void markSeen(String from, String msgId) {
        dedupeWindow.markSeen(from, msgId);
    }
}
