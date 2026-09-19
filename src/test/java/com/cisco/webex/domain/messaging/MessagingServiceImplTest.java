package com.cisco.webex.domain.messaging;

import com.cisco.webex.domain.client.Client;
import com.cisco.webex.domain.client.ClientChannel;
import com.cisco.webex.domain.client.ClientDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingServiceImplTest {

    @Mock
    private ClientDirectory clientDirectory;
    @Mock
    private MailboxRepository mailbox;
    @Mock
    private MessageBroker broker;

    private MessagingServiceImpl service;

    @BeforeEach
    void setUp() {
        MessagingServiceImpl.Limits limits = new MessagingServiceImpl.Limits(1024, 2);
        service = new MessagingServiceImpl(clientDirectory, mailbox, broker, limits);
    }

    private Message message(String msgId, DeliveryState status) {
        return new Message(msgId, "alice", "bob", "hi", status);
    }

    // ---- register ----

    @Test
    void registerAttachesChannelAndReturnsOk() {
        Client client = mock(Client.class);
        when(clientDirectory.getOrCreate("bob")).thenReturn(client);
        when(mailbox.findByToAndStatusOrderByCreatedAtAsc("bob", DeliveryState.UNACKED)).thenReturn(List.of());
        when(mailbox.findByToAndStatusOrderByCreatedAtAsc("bob", DeliveryState.QUEUED)).thenReturn(List.of());
        ClientChannel channel = mock(ClientChannel.class);

        RegisterResult result = service.register("bob", channel);

        assertThat(result.status()).isEqualTo(RegisterResult.Status.OK);
        assertThat(result.session()).isSameAs(client);
        verify(client).attach(channel);
        verify(client, never()).tryDeliver(any());
        verify(mailbox, never()).save(any());
    }

    @Test
    void registerRedeliversUnackedMessagesInOrder() {
        Client client = mock(Client.class);
        when(clientDirectory.getOrCreate("bob")).thenReturn(client);
        Message m1 = message("m1", DeliveryState.UNACKED);
        Message m2 = message("m2", DeliveryState.UNACKED);
        when(mailbox.findByToAndStatusOrderByCreatedAtAsc("bob", DeliveryState.UNACKED)).thenReturn(List.of(m1, m2));
        when(mailbox.findByToAndStatusOrderByCreatedAtAsc("bob", DeliveryState.QUEUED)).thenReturn(List.of());

        service.register("bob", mock(ClientChannel.class));

        InOrder order = inOrder(client);
        order.verify(client).tryDeliver(m1);
        order.verify(client).tryDeliver(m2);
        verify(mailbox, never()).save(any());
    }

    @Test
    void registerDeliversQueuedMessagesAndPromotesThemToUnacked() {
        Client client = mock(Client.class);
        when(clientDirectory.getOrCreate("bob")).thenReturn(client);
        Message m1 = message("m1", DeliveryState.QUEUED);
        when(mailbox.findByToAndStatusOrderByCreatedAtAsc("bob", DeliveryState.UNACKED)).thenReturn(List.of());
        when(mailbox.findByToAndStatusOrderByCreatedAtAsc("bob", DeliveryState.QUEUED)).thenReturn(List.of(m1));
        when(client.tryDeliver(m1)).thenReturn(true);

        service.register("bob", mock(ClientChannel.class));

        assertThat(m1.getStatus()).isEqualTo(DeliveryState.UNACKED);
        verify(mailbox).save(m1);
    }

    @Test
    void registerLeavesQueuedMessageUnchangedWhenDeliveryFails() {
        Client client = mock(Client.class);
        when(clientDirectory.getOrCreate("bob")).thenReturn(client);
        Message m1 = message("m1", DeliveryState.QUEUED);
        when(mailbox.findByToAndStatusOrderByCreatedAtAsc("bob", DeliveryState.UNACKED)).thenReturn(List.of());
        when(mailbox.findByToAndStatusOrderByCreatedAtAsc("bob", DeliveryState.QUEUED)).thenReturn(List.of(m1));
        when(client.tryDeliver(m1)).thenReturn(false);

        service.register("bob", mock(ClientChannel.class));

        assertThat(m1.getStatus()).isEqualTo(DeliveryState.QUEUED);
        verify(mailbox, never()).save(any());
    }

    // ---- send ----

    @Test
    void sendRejectsBlankRecipient() {
        // Guards against a real bug this caught: ClientDirectory is backed by
        // a ConcurrentHashMap, which throws NullPointerException on a null
        // key rather than returning "not found" — a missing `to` must be
        // rejected here, before it ever reaches that lookup.
        SendResult result = service.send("alice", "  ", "m1", "hi");

        assertThat(result.status()).isEqualTo(SendResult.Status.REJECTED);
        assertThat(result.code()).isEqualTo("INVALID_RECIPIENT");
        verifyNoInteractions(clientDirectory, broker, mailbox);
    }

    @Test
    void sendRejectsBlankMsgId() {
        SendResult result = service.send("alice", "bob", "  ", "hi");

        assertThat(result.status()).isEqualTo(SendResult.Status.REJECTED);
        assertThat(result.code()).isEqualTo("INVALID_MSG_ID");
        verifyNoInteractions(clientDirectory, broker, mailbox);
    }

    @Test
    void sendRejectsOversizedPayload() {
        String bigPayload = "x".repeat(2000); // > 1024 configured limit

        SendResult result = service.send("alice", "bob", "m1", bigPayload);

        assertThat(result.status()).isEqualTo(SendResult.Status.REJECTED);
        assertThat(result.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        verifyNoInteractions(clientDirectory, broker, mailbox);
    }

    @Test
    void sendRejectsUnknownRecipient() {
        when(clientDirectory.find("bob")).thenReturn(Optional.empty());

        SendResult result = service.send("alice", "bob", "m1", "hi");

        assertThat(result.status()).isEqualTo(SendResult.Status.REJECTED);
        assertThat(result.code()).isEqualTo("UNKNOWN_RECIPIENT");
        verifyNoInteractions(broker, mailbox);
    }

    @Test
    void sendOfDuplicateMsgIdIsAcceptedIdempotentlyWithoutPublishing() {
        Client recipient = mock(Client.class);
        when(clientDirectory.find("bob")).thenReturn(Optional.of(recipient));
        when(recipient.isDuplicate("alice", "m1")).thenReturn(true);

        SendResult result = service.send("alice", "bob", "m1", "hi");

        assertThat(result.status()).isEqualTo(SendResult.Status.ACCEPTED);
        verify(recipient, never()).markSeen(any(), any());
        verifyNoInteractions(broker);
        verifyNoInteractions(mailbox);
    }

    @Test
    void sendOfSameMsgIdFromADifferentSenderIsNotTreatedAsDuplicate() {
        // isDuplicate is scoped by (from, msgId): "carol" reusing an id that
        // "alice" already used against the same recipient must not collide
        // with alice's send — msgId is only a sender's own idempotency key.
        Client recipient = mock(Client.class);
        when(clientDirectory.find("bob")).thenReturn(Optional.of(recipient));
        when(recipient.isDuplicate("carol", "m1")).thenReturn(false);
        when(mailbox.countByTo("bob")).thenReturn(0L);
        when(broker.publish(any())).thenReturn(true);

        SendResult result = service.send("carol", "bob", "m1", "hi from carol");

        assertThat(result.status()).isEqualTo(SendResult.Status.ACCEPTED);
        verify(broker).publish(argThatMatches("m1", "carol", "bob", "hi from carol"));
        verify(recipient).markSeen("carol", "m1");
    }

    @Test
    void sendRejectsWhenRecipientMailboxIsFull() {
        Client recipient = mock(Client.class);
        when(clientDirectory.find("bob")).thenReturn(Optional.of(recipient));
        when(recipient.isDuplicate("alice", "m1")).thenReturn(false);
        when(mailbox.countByTo("bob")).thenReturn(2L); // configured max is 2

        SendResult result = service.send("alice", "bob", "m1", "hi");

        assertThat(result.status()).isEqualTo(SendResult.Status.REJECTED);
        assertThat(result.code()).isEqualTo("MAILBOX_FULL");
        verifyNoInteractions(broker);
        verify(recipient, never()).markSeen(any(), any());
    }

    @Test
    void sendAcceptsAndPublishesToTheBroker() {
        Client recipient = mock(Client.class);
        when(clientDirectory.find("bob")).thenReturn(Optional.of(recipient));
        when(recipient.isDuplicate("alice", "m1")).thenReturn(false);
        when(mailbox.countByTo("bob")).thenReturn(0L);
        when(broker.publish(any())).thenReturn(true);

        SendResult result = service.send("alice", "bob", "m1", "hi");

        assertThat(result.status()).isEqualTo(SendResult.Status.ACCEPTED);
        verify(broker).publish(argThatMatches("m1", "alice", "bob", "hi"));
        verify(recipient).markSeen("alice", "m1");
    }

    @Test
    void sendRejectsWhenBrokerPublishFailsAndDoesNotMarkSeen() {
        Client recipient = mock(Client.class);
        when(clientDirectory.find("bob")).thenReturn(Optional.of(recipient));
        when(recipient.isDuplicate("alice", "m1")).thenReturn(false);
        when(mailbox.countByTo("bob")).thenReturn(0L);
        when(broker.publish(any())).thenReturn(false); // broker at capacity

        SendResult result = service.send("alice", "bob", "m1", "hi");

        assertThat(result.status()).isEqualTo(SendResult.Status.REJECTED);
        assertThat(result.code()).isEqualTo("BROKER_UNAVAILABLE");
        // Must not mark seen: a retry with the same msgId must be treated
        // as a fresh attempt, not swallowed by the dedupe check as a
        // phantom "already accepted" for a message that was never queued.
        verify(recipient, never()).markSeen(any(), any());
    }

    private Message argThatMatches(String msgId, String from, String to, String payload) {
        return org.mockito.ArgumentMatchers.argThat(m ->
                m.getMsgId().equals(msgId) && m.getFrom().equals(from)
                        && m.getTo().equals(to) && m.getPayload().equals(payload));
    }

    // ---- ack ----

    @Test
    void ackDeletesTheMatchingMailboxRow() {
        service.ack("bob", "alice", "m1");

        verify(mailbox).deleteByToAndFromAndMsgId("bob", "alice", "m1");
    }

    @Test
    void ackIsScopedBySenderSoItCannotRemoveADifferentSendersMessage() {
        // Two different senders may independently pick the same msgId for
        // the same recipient (msgId is only unique within one sender's own
        // retries) — acking one must never touch the other's still-pending
        // message with the same id.
        service.ack("bob", "alice", "m1");

        verify(mailbox).deleteByToAndFromAndMsgId("bob", "alice", "m1");
        verify(mailbox, never()).deleteByToAndFromAndMsgId("bob", "carol", "m1");
    }

    @Test
    void staleAckIsANoOpAndDoesNotThrow() {
        // deleteByToAndFromAndMsgId is void; Spring Data no-ops silently if nothing matches.
        service.ack("bob", "alice", "never-sent");

        verify(mailbox).deleteByToAndFromAndMsgId("bob", "alice", "never-sent");
    }

    // ---- disconnect ----

    @Test
    void disconnectDetachesAKnownClient() {
        Client client = mock(Client.class);
        when(clientDirectory.find("bob")).thenReturn(Optional.of(client));

        service.disconnect("bob");

        verify(client).detach();
    }

    @Test
    void disconnectOfUnknownClientIsANoOp() {
        when(clientDirectory.find("ghost")).thenReturn(Optional.empty());

        service.disconnect("ghost");
        // No exception; nothing else to verify — there is no client to detach.
    }

    // ---- deliver ----

    @Test
    void deliverToOnlineRecipientMarksUnackedAndSaves() {
        Message message = message("m1", DeliveryState.QUEUED);
        Client recipient = mock(Client.class);
        when(clientDirectory.find("bob")).thenReturn(Optional.of(recipient));
        when(recipient.tryDeliver(message)).thenReturn(true);

        service.deliver(message);

        assertThat(message.getStatus()).isEqualTo(DeliveryState.UNACKED);
        verify(mailbox).save(message);
    }

    @Test
    void deliverFallsBackToQueuedWhenChannelRejects() {
        Message message = message("m1", DeliveryState.QUEUED);
        Client recipient = mock(Client.class);
        when(clientDirectory.find("bob")).thenReturn(Optional.of(recipient));
        when(recipient.tryDeliver(message)).thenReturn(false);

        service.deliver(message);

        assertThat(message.getStatus()).isEqualTo(DeliveryState.QUEUED);
        verify(mailbox).save(message);
    }

    @Test
    void deliverToUnknownRecipientStoresQueuedDefensively() {
        Message message = message("m1", DeliveryState.QUEUED);
        when(clientDirectory.find("bob")).thenReturn(Optional.empty());

        service.deliver(message);

        assertThat(message.getStatus()).isEqualTo(DeliveryState.QUEUED);
        verify(mailbox).save(message);
    }
}
