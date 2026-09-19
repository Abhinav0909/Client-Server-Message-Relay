package com.cisco.webex.orchestration;

import com.cisco.webex.domain.messaging.Message;
import com.cisco.webex.domain.messaging.MessageBroker;
import com.cisco.webex.domain.messaging.MessagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Driving adapter: polls {@link MessageBroker#consume()} on its own thread
 * and calls {@link MessagingService#deliver(Message)} for each message,
 * then commits. Carries no delivery policy of its own — that all lives in
 * {@link MessagingService}; this class only notices work and hands it off.
 */
@Component
public class QueueConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QueueConsumer.class);
    private static final long POLL_INTERVAL_MS = 50;

    private final MessageBroker broker;
    private final MessagingService messagingService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumerThread;

    public QueueConsumer(MessageBroker broker, MessagingService messagingService) {
        this.broker = broker;
        this.messagingService = messagingService;
    }

    @Override
    public void start() {
        running.set(true);
        consumerThread = Thread.ofVirtual().start(this::consumeLoop);
        log.info("QueueConsumer started");
    }

    private void consumeLoop() {
        while (running.get()) {
            if (!processOneIfPresent()) {
                sleepQuietly();
            }
        }
    }

    /** Processes one pending message, if any. Used by tests to avoid sleeping on the poll loop. */
    public boolean processOneIfPresent() {
        Message queued = broker.consume();
        if (queued == null) {
            return false;
        }
        messagingService.deliver(queued);
        broker.commit(queued.getMsgId());
        return true;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops the poll loop and waits for any in-flight message to finish processing. */
    @Override
    public void stop() {
        running.set(false);
        if (consumerThread != null) {
            consumerThread.interrupt();
            try {
                consumerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("QueueConsumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
