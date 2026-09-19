package com.cisco.webex.broker;

import com.cisco.webex.domain.messaging.Message;
import com.cisco.webex.domain.messaging.MessageBroker;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * In-process, bounded-queue implementation of {@link MessageBroker}.
 * Swappable for Postgres/Kafka/etc. later without touching domain code,
 * since callers only depend on the {@link MessageBroker} interface.
 *
 * Not a {@code @Component}: the bounded constructor needs a config value
 * ({@code relay.broker.max-queue-size}), so the bean is created explicitly
 * in {@code ApplicationConfig} rather than via component scan.
 */
public class InMemoryMessageBroker implements MessageBroker {

    private final BlockingQueue<Message> queue;
    private final Map<String, Message> inFlight = new ConcurrentHashMap<>();

    /** Unbounded queue — used as the default Spring-managed bean. */
    public InMemoryMessageBroker() {
        this.queue = new LinkedBlockingQueue<>();
    }

    /** Bounded queue, capacity in message count. */
    public InMemoryMessageBroker(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public boolean publish(Message message) {
        return queue.offer(message);
    }

    @Override
    public Message consume() {
        Message next = queue.poll();
        if (next != null) {
            inFlight.put(next.getMsgId(), next);
        }
        return next;
    }

    @Override
    public void commit(String msgId) {
        inFlight.remove(msgId);
    }

    /** Returns queued message count. */
    public int pendingCount() {
        return queue.size();
    }

    public int inFlightCount() {
        return inFlight.size();
    }
}
