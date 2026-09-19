package com.cisco.webex.domain.messaging;

import com.cisco.webex.domain.client.Client;
import com.cisco.webex.domain.client.ClientChannel;
import com.cisco.webex.domain.client.ClientDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The core orchestrator — every business rule in {@link MessagingService}'s
 * Javadoc lives in this class. Depends only on ports ({@link ClientDirectory},
 * {@link MailboxRepository}, {@link MessageBroker}) and configuration, never on
 * transport or wire-format types.
 */
@Service
public class MessagingServiceImpl implements MessagingService {

    public record Limits(int maxPayloadBytes, int maxMailboxSize) {}

    private static final Logger log = LoggerFactory.getLogger(MessagingServiceImpl.class);

    private final ClientDirectory clientDirectory;
    private final MailboxRepository mailbox;
    private final MessageBroker broker;
    private final int maxPayloadBytes;
    private final int maxMailboxSize;

    public MessagingServiceImpl(ClientDirectory clientDirectory, MailboxRepository mailbox, MessageBroker broker,
                                 Limits limits) {
        this.clientDirectory = clientDirectory;
        this.mailbox = mailbox;
        this.broker = broker;
        this.maxPayloadBytes = limits.maxPayloadBytes();
        this.maxMailboxSize = limits.maxMailboxSize();
    }

    @Override
    public RegisterResult register(String clientId, ClientChannel channel) {
        Optional<RegisterResult> rejection = validateRegisterRequest(clientId);
        if (rejection.isPresent()) {
            return rejection.get();
        }

        Client client = clientDirectory.getOrCreate(clientId);
        client.attach(channel);
        log.info("Registered clientId={}", clientId);

        redeliverPending(clientId, client);

        return RegisterResult.ok(client);
    }

    /** Pure input-shape check for {@link #register} — same pattern as {@link #validateSendRequest}. */
    private Optional<RegisterResult> validateRegisterRequest(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.of(RegisterResult.error("INVALID_CLIENT_ID", "clientId must be non-empty"));
        }
        return Optional.empty();
    }

    private void redeliverPending(String clientId, Client client) {
        List<Message> unacked = mailbox.findByToAndStatusOrderByCreatedAtAsc(clientId, DeliveryState.UNACKED);
        for (Message message : unacked) {
            client.tryDeliver(message);
        }

        List<Message> queued = mailbox.findByToAndStatusOrderByCreatedAtAsc(clientId, DeliveryState.QUEUED);
        for (Message message : queued) {
            if (client.tryDeliver(message)) {
                message.setStatus(DeliveryState.UNACKED);
                mailbox.save(message);
            }
        }
    }

    @Override
    public SendResult send(String fromId, String toId, String msgId, String payload) {
        Optional<SendResult> rejection = validateSendRequest(toId, msgId, payload);
        if (rejection.isPresent()) {
            return rejection.get();
        }

        Optional<Client> recipientLookup = clientDirectory.find(toId);
        if (recipientLookup.isEmpty()) {
            return SendResult.rejected("UNKNOWN_RECIPIENT", "no such client: " + toId);
        }
        Client recipient = recipientLookup.get();

        if (recipient.isDuplicate(fromId, msgId)) {
            log.debug("Duplicate SEND msgId={} from={} to={} treated as idempotent accept", msgId, fromId, toId);
            return SendResult.accepted();
        }

        if (mailbox.countByTo(toId) >= maxMailboxSize) {
            return SendResult.rejected("MAILBOX_FULL", "mailbox for " + toId + " is at capacity");
        }

        boolean published = broker.publish(new Message(msgId, fromId, toId, payload, DeliveryState.QUEUED));
        if (!published) {
            // Not marking seen: if the sender retries with the same msgId,
            // that retry must be treated as a fresh attempt, not silently
            // swallowed by the dedupe check as a phantom "already accepted."
            log.warn("Broker rejected publish of msgId={} to={}: queue at capacity", msgId, toId);
            return SendResult.rejected("BROKER_UNAVAILABLE", "message queue is at capacity, try again");
        }

        recipient.markSeen(fromId, msgId);
        return SendResult.accepted();
    }

    /**
     * Pure input-shape checks for {@link #send} — no collaborator calls, no
     * business decision, just "is this request even well-formed." Separate
     * from the recipient/duplicate/capacity/broker checks below it, which
     * each need a collaborator and are business rules, not validation.
     *
     * @return the rejection to return, or empty if the request is well-formed
     */
    private Optional<SendResult> validateSendRequest(String toId, String msgId, String payload) {
        if (toId == null || toId.isBlank()) {
            // Checked ahead of the recipient lookup below: ClientDirectory is
            // backed by a ConcurrentHashMap, which throws NullPointerException
            // on a null key rather than just returning "not found."
            return Optional.of(SendResult.rejected("INVALID_RECIPIENT", "to must be non-empty"));
        }
        if (msgId == null || msgId.isBlank()) {
            return Optional.of(SendResult.rejected("INVALID_MSG_ID", "msgId must be non-empty"));
        }
        if (payload != null && payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            return Optional.of(SendResult.rejected("PAYLOAD_TOO_LARGE",
                    "payload exceeds max of " + maxPayloadBytes + " bytes"));
        }
        return Optional.empty();
    }

    @Override
    public void ack(String clientId, String fromId, String msgId) {
        mailbox.deleteByToAndFromAndMsgId(clientId, fromId, msgId);
    }

    @Override
    public void disconnect(String clientId) {
        clientDirectory.find(clientId).ifPresent(Client::detach);
        log.info("Disconnected clientId={}", clientId);
    }

    @Override
    public void deliver(Message message) {
        Optional<Client> recipient = clientDirectory.find(message.getTo());
        boolean delivered = recipient.isPresent() && recipient.get().tryDeliver(message);

        message.setStatus(delivered ? DeliveryState.UNACKED : DeliveryState.QUEUED);
        mailbox.save(message);
    }
}
