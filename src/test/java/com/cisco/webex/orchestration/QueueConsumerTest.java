package com.cisco.webex.orchestration;

import com.cisco.webex.domain.messaging.DeliveryState;
import com.cisco.webex.domain.messaging.Message;
import com.cisco.webex.domain.messaging.MessageBroker;
import com.cisco.webex.domain.messaging.MessagingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueueConsumerTest {

    private final MessageBroker broker = mock(MessageBroker.class);
    private final MessagingService messagingService = mock(MessagingService.class);
    private final QueueConsumer consumer = new QueueConsumer(broker, messagingService);

    @AfterEach
    void tearDown() {
        consumer.stop();
    }

    @Test
    void processOneIfPresentDeliversThenCommitsInOrder() {
        Message message = new Message("m1", "alice", "bob", "hi", DeliveryState.QUEUED);
        when(broker.consume()).thenReturn(message);

        boolean processed = consumer.processOneIfPresent();

        assertThat(processed).isTrue();
        InOrder order = inOrder(messagingService, broker);
        order.verify(messagingService).deliver(message);
        order.verify(broker).commit("m1");
    }

    @Test
    void processOneIfPresentOnEmptyQueueDoesNothing() {
        when(broker.consume()).thenReturn(null);

        boolean processed = consumer.processOneIfPresent();

        assertThat(processed).isFalse();
        verify(messagingService, never()).deliver(org.mockito.ArgumentMatchers.any());
        verify(broker, never()).commit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void startMakesItRunningAndStopMakesItNot() {
        assertThat(consumer.isRunning()).isFalse();

        consumer.start();
        assertThat(consumer.isRunning()).isTrue();

        consumer.stop();
        assertThat(consumer.isRunning()).isFalse();
    }
}
