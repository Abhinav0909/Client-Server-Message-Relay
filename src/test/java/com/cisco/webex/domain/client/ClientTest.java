package com.cisco.webex.domain.client;

import com.cisco.webex.domain.messaging.DeliveryState;
import com.cisco.webex.domain.messaging.Message;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientTest {

    private Message someMessage() {
        return new Message("m1", "alice", "bob", "hi", DeliveryState.QUEUED);
    }

    @Test
    void constructorSetsClientId() {
        Client client = new Client("alice", 1000);
        assertThat(client.getClientId()).isEqualTo("alice");
    }

    @Test
    void freshClientIsOffline() {
        Client client = new Client("alice", 1000);
        assertThat(client.isOnline()).isFalse();
    }

    @Test
    void attachMakesClientOnline() {
        Client client = new Client("alice", 1000);
        client.attach(mock(ClientChannel.class));
        assertThat(client.isOnline()).isTrue();
    }

    @Test
    void detachMakesClientOffline() {
        Client client = new Client("alice", 1000);
        client.attach(mock(ClientChannel.class));
        client.detach();
        assertThat(client.isOnline()).isFalse();
    }

    @Test
    void reattachClosesThePreviousChannel() {
        Client client = new Client("alice", 1000);
        ClientChannel first = mock(ClientChannel.class);
        ClientChannel second = mock(ClientChannel.class);

        client.attach(first);
        client.attach(second);

        verify(first).close();
        verify(second, never()).close();
    }

    @Test
    void tryDeliverWhenOfflineReturnsFalseWithoutThrowing() {
        Client client = new Client("alice", 1000);
        assertThat(client.tryDeliver(someMessage())).isFalse();
    }

    @Test
    void tryDeliverWhenOnlineDelegatesToTheAttachedChannel() {
        Client client = new Client("alice", 1000);
        ClientChannel channel = mock(ClientChannel.class);
        Message message = someMessage();
        when(channel.deliver(message)).thenReturn(true);
        client.attach(channel);

        boolean result = client.tryDeliver(message);

        assertThat(result).isTrue();
        verify(channel).deliver(message);
    }

    @Test
    void tryDeliverPropagatesChannelRejection() {
        Client client = new Client("alice", 1000);
        ClientChannel channel = mock(ClientChannel.class);
        Message message = someMessage();
        when(channel.deliver(message)).thenReturn(false);
        client.attach(channel);

        assertThat(client.tryDeliver(message)).isFalse();
    }

    @Test
    void freshClientHasNoDuplicates() {
        Client client = new Client("alice", 1000);
        assertThat(client.isDuplicate("sender", "m1")).isFalse();
    }

    @Test
    void markSeenThenIsDuplicateIsTrue() {
        Client client = new Client("alice", 1000);
        client.markSeen("sender", "m1");
        assertThat(client.isDuplicate("sender", "m1")).isTrue();
    }

    @Test
    void sameMsgIdFromDifferentSendersIsNotADuplicate() {
        Client client = new Client("alice", 1000);
        client.markSeen("sender1", "m1");
        assertThat(client.isDuplicate("sender2", "m1")).isFalse();
    }

    @Test
    void dedupeWindowEvictsOldestEntryOnceFull() {
        Client client = new Client("alice", 1000);
        for (int i = 0; i < 1000; i++) {
            client.markSeen("sender", "m" + i);
        }
        assertThat(client.isDuplicate("sender", "m0")).isTrue();

        client.markSeen("sender", "m1000"); // 1001st distinct id, forces eviction of m0

        assertThat(client.isDuplicate("sender", "m0")).isFalse();
        assertThat(client.isDuplicate("sender", "m1000")).isTrue();
    }
}
