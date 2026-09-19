package com.cisco.webex.broker;

import com.cisco.webex.domain.messaging.DeliveryState;
import com.cisco.webex.domain.messaging.Message;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMessageBrokerTest {

    private Message message(String msgId) {
        return new Message(msgId, "alice", "bob", "hi", DeliveryState.QUEUED);
    }

    @Test
    void publishThenConsumeReturnsTheMessage() {
        InMemoryMessageBroker broker = new InMemoryMessageBroker();

        assertThat(broker.publish(message("m1"))).isTrue();

        Message consumed = broker.consume();

        assertThat(consumed).isNotNull();
        assertThat(consumed.getMsgId()).isEqualTo("m1");
        assertThat(broker.pendingCount()).isZero();
        assertThat(broker.inFlightCount()).isEqualTo(1);
    }

    @Test
    void publishReturnsFalseWhenQueueIsFull() {
        InMemoryMessageBroker broker = new InMemoryMessageBroker(1);

        assertThat(broker.publish(message("m1"))).isTrue();
        assertThat(broker.publish(message("m2"))).isFalse();
        assertThat(broker.pendingCount()).isEqualTo(1);
    }

    @Test
    void commitRemovesFromInFlightAndDoesNotReturnAgain() {
        InMemoryMessageBroker broker = new InMemoryMessageBroker();
        broker.publish(message("m1"));
        Message consumed = broker.consume();

        broker.commit(consumed.getMsgId());

        assertThat(broker.inFlightCount()).isZero();
        assertThat(broker.consume()).isNull();
    }

    @Test
    void consumeOnEmptyQueueReturnsNull() {
        InMemoryMessageBroker broker = new InMemoryMessageBroker();
        assertThat(broker.consume()).isNull();
    }
}
