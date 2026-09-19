package com.cisco.webex.domain.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A message relayed between two clients. Also the JPA entity for mailbox
 * storage — an accepted domain/persistence coupling for this POC (see
 * APPROACH.md): {@code MailboxRepository} is a Spring Data repository directly over
 * this type rather than a hand-mapped adapter over a separate entity.
 *
 * Pure data holder — no branching logic; other classes' tests construct
 * these as fixtures.
 */
@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_to_status_created", columnList = "recipient_id, status, created_at")
})
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Sender-supplied identifier; unique per (to, msgId) pair, not globally. */
    private String msgId;

    @Column(name = "sender_id")
    private String from;

    @Column(name = "recipient_id")
    private String to;

    @Lob
    private String payload;

    @Enumerated(EnumType.STRING)
    private DeliveryState status;

    @Column(name = "created_at")
    private Instant createdAt;

    /** Required by JPA. */
    protected Message() {
    }

    public Message(String msgId, String from, String to, String payload, DeliveryState status) {
        this.msgId = msgId;
        this.from = from;
        this.to = to;
        this.payload = payload;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getMsgId() {
        return msgId;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public String getPayload() {
        return payload;
    }

    public DeliveryState getStatus() {
        return status;
    }

    public void setStatus(DeliveryState status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
