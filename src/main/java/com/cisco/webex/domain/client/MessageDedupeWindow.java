package com.cisco.webex.domain.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, FIFO-evicting record of recently-seen (sender, msgId) pairs, used
 * to detect duplicate SEND requests. Keyed by sender as well as msgId: msgId
 * is a sender-supplied idempotency key, unique only within that sender's own
 * retries, not across unrelated senders addressing the same recipient.
 * Separate from {@link Client}: "who is this client" (identity, presence)
 * and "have I seen this message before" (idempotency) are different
 * concerns.
 */
public class MessageDedupeWindow {

    /** Composite key, not a concatenated string — avoids ambiguous-boundary collisions entirely. */
    private record Key(String from, String msgId) {}

    private final int capacity;
    private final Set<Key> seen = ConcurrentHashMap.newKeySet();
    private final Deque<Key> order = new ArrayDeque<>();

    public MessageDedupeWindow(int capacity) {
        this.capacity = capacity;
    }

    public boolean isDuplicate(String from, String msgId) {
        return seen.contains(new Key(from, msgId));
    }

    /** Records (from, msgId) as seen, evicting the oldest entry once the window is full. */
    public synchronized void markSeen(String from, String msgId) {
        Key key = new Key(from, msgId);
        if (seen.add(key)) {
            order.addLast(key);
            if (order.size() > capacity) {
                Key evicted = order.pollFirst();
                seen.remove(evicted);
            }
        }
    }
}
