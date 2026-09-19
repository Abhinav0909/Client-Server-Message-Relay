package com.cisco.webex.domain.messaging;

/**
 * Publish/consume port decoupling "accepted" (send) from "delivered"
 * (deliver). See {@link MessagingService#send} for what publish actually
 * commits to.
 *
 * Implemented by {@link com.cisco.webex.broker.InMemoryMessageBroker};
 * never implemented in domain code.
 */
public interface MessageBroker {

    /** @return true if accepted onto the queue, false if the queue is at capacity. */
    boolean publish(Message message);

    /** @return the next message, or null if the queue is currently empty. */
    Message consume();

    /** Marks a previously consumed message as fully processed. */
    void commit(String msgId);
}
