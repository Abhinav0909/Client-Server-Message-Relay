package com.cisco.webex.domain.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Durable per-recipient message storage. Spring Data supplies the
 * implementation directly — no hand-written adapter class exists for
 * this interface.
 */
public interface MailboxRepository extends JpaRepository<Message, Long> {

    /** FIFO within a status, oldest first. */
    List<Message> findByToAndStatusOrderByCreatedAtAsc(String to, DeliveryState status);

    /** Total rows for this recipient regardless of status — the capacity check used by send(). */
    long countByTo(String to);

    /** Scoped by recipient: an ACK from the wrong client can never match a row here. */
    Optional<Message> findByToAndMsgId(String to, String msgId);

    /**
     * Scoped by sender as well as recipient and msgId: msgId is only unique
     * within one sender's own messages to a given recipient, so an ack must
     * never match a different sender's message that happens to reuse the
     * same id. Unlike {@code save}/{@code deleteById} (inherited from
     * JpaRepository, already transactional), a custom derived delete query
     * needs its own {@code @Transactional} — it isn't wrapped automatically.
     */
    @Transactional
    void deleteByToAndFromAndMsgId(String to, String from, String msgId);
}
