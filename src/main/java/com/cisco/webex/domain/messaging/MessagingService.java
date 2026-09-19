package com.cisco.webex.domain.messaging;

import com.cisco.webex.domain.client.ClientChannel;

/**
 * The single business contract for the relay. Callers (transport, and the
 * broker-driven worker) never see Frame, socket, or storage/broker types.
 */
public interface MessagingService {

    /**
     * Registers clientId against this channel, evicting/closing any
     * previous channel for the same id. If this client has QUEUED or
     * UNACKED messages waiting, they are redelivered as part of this same
     * call, before returning — callers do not invoke a separate flush.
     */
    RegisterResult register(String clientId, ClientChannel channel);

    /**
     * Validates and accepts a message for delivery. Checks, in order:
     * msgId present -&gt; payload within size limit -&gt; recipient known -&gt;
     * not a duplicate msgId for this recipient -&gt; recipient's mailbox has
     * room (countByTo below capacity).
     *
     * On ACCEPTED, the message is durably published via {@link MessageBroker}
     * and this call returns immediately. ACCEPTED means delivery is
     * guaranteed to be attempted at-least-once — it does NOT mean the
     * recipient has received it yet, even if they're currently online.
     * Actual delivery happens asynchronously via {@link #deliver(Message)},
     * called later by the broker's consumer worker.
     */
    SendResult send(String fromId, String toId, String msgId, String payload);

    /**
     * Acknowledges msgId for clientId, removing it from that client's
     * mailbox. Scoped by fromId as well as msgId: two different senders may
     * independently choose the same msgId for the same recipient, and an ACK
     * must remove only the specific message it targets, never an unrelated
     * one that happens to share that id. A stale or repeated ACK (row
     * already removed, or never existed) is a silent no-op — ACK has no
     * response frame by protocol.
     */
    void ack(String clientId, String fromId, String msgId);

    /** Marks clientId offline. The Client identity and mailbox contents remain. */
    void disconnect(String clientId);

    /**
     * Completes delivery for one message pulled off the broker: if the
     * recipient is online, pushes it via their ClientChannel and marks
     * UNACKED; otherwise stores it QUEUED. Called only by the broker's
     * consumer worker, never by transport code.
     */
    void deliver(Message message);
}
