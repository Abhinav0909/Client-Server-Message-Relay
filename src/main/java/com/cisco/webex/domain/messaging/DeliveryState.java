package com.cisco.webex.domain.messaging;

/**
 * Lifecycle of a message inside a recipient's mailbox.
 * QUEUED   — stored, recipient offline or not yet attempted.
 * UNACKED  — handed to the client's channel, awaiting ACK. May be
 *            redelivered (at-least-once) on reconnect if never acked.
 * There is no ACKED state — an acknowledged message is deleted, not
 * transitioned.
 */
public enum DeliveryState {
    QUEUED,
    UNACKED
}
