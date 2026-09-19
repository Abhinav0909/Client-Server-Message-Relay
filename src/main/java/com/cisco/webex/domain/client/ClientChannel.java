package com.cisco.webex.domain.client;

import com.cisco.webex.domain.messaging.Message;

/**
 * A live route to exactly one client. An instance is bound to a single
 * client's connection for its lifetime; it does not carry a recipient id
 * because the recipient is implicit — whoever this channel was obtained for.
 *
 * Implemented by orchestration adapters (e.g.
 * {@link com.cisco.webex.orchestration.ClientConnection}); never
 * implemented or constructed by domain code.
 */
public interface ClientChannel {

    /**
     * Pushes a message down this channel to the client it represents.
     *
     * @return true if handed off to the transport for writing, false if
     *         the channel could not accept it (e.g. write buffer full,
     *         already closed). A false result means the caller must fall
     *         back to mailbox storage — this call never blocks or retries.
     */
    boolean deliver(Message message);

    /** Closes the underlying connection. Idempotent. */
    void close();
}
