package com.cisco.webex.orchestration;

import com.cisco.webex.domain.client.ClientChannel;
import com.cisco.webex.domain.messaging.Message;
import com.cisco.webex.domain.messaging.MessagingService;
import com.cisco.webex.domain.messaging.RegisterResult;
import com.cisco.webex.domain.messaging.SendResult;
import com.cisco.webex.protocol.Frame;
import com.cisco.webex.protocol.FrameCodec;
import com.cisco.webex.protocol.Operations;
import com.cisco.webex.protocol.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Driving adapter: reads frames off one client socket, translates each into
 * a call on {@link MessagingService} (REGISTER/SEND/ACK), and writes
 * outgoing frames (including DELIVER, via {@link #deliver(Message)}) on its
 * own writer thread. Carries no business policy of its own — every decision
 * is made by MessagingService, this class only translates. One instance per
 * accepted socket, not a Spring bean.
 */
public class ClientConnection implements ClientChannel {

    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

    private final Socket socket;
    private final FrameCodec codec;
    private final MessagingService messagingService;

    private final BlockingQueue<Frame> writeQueue;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile String clientId; // Set after REGISTER.
    private volatile Thread writerThread;

    public ClientConnection(Socket socket, FrameCodec codec, MessagingService messagingService,
                             int writeQueueCapacity) {
        this.socket = socket;
        this.codec = codec;
        this.messagingService = messagingService;
        this.writeQueue = new LinkedBlockingQueue<>(writeQueueCapacity);
    }

    /** Reads frames from the socket until the connection closes or fails. Blocks the calling thread. */
    public void run() {
        startWriterThread();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            Frame frame;
            while (!closed.get() && (frame = safeDecode(reader)) != null) {
                handleFrame(frame);
            }
        } catch (IOException e) {
            log.debug("Connection I/O ended for clientId={}: {}", clientId, e.getMessage());
        } finally {
            close();
        }
    }

    private Frame safeDecode(BufferedReader reader) throws IOException {
        try {
            return codec.decode(reader);
        } catch (ProtocolException e) {
            log.info("Protocol error from clientId={}: {}", clientId, e.getMessage());
            Frame err = Frame.of(Operations.ERROR);
            err.setCode("PROTOCOL_ERROR");
            err.setReason(e.getMessage());
            sendFrame(err);
            waitForWriteQueueDrain(500);
            return null; // Ends the read loop.
        }
    }

    private void waitForWriteQueueDrain(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!writeQueue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void handleFrame(Frame frame) {
        if (frame.getOp() == null) {
            Frame err = Frame.of(Operations.ERROR);
            err.setCode("MISSING_OP");
            err.setReason("frame is missing 'op'");
            sendFrame(err);
            return;
        }

        try {
            switch (frame.getOp()) {
                case REGISTER -> handleRegister(frame);
                case SEND -> handleSend(frame);
                case ACK -> handleAck(frame);
                default -> {
                    Frame err = Frame.of(Operations.ERROR);
                    err.setCode("UNSUPPORTED_OP");
                    err.setReason("client may not send op=" + frame.getOp());
                    sendFrame(err);
                }
            }
        } catch (RuntimeException e) {
            // An unexpected failure in one request must not take down an
            // otherwise-healthy connection or affect unrelated clients.
            log.warn("Unexpected error handling op={} for clientId={}: {}", frame.getOp(), clientId, e.toString());
            Frame err = Frame.of(Operations.ERROR);
            err.setCode("INTERNAL_ERROR");
            err.setReason("failed to process op=" + frame.getOp());
            sendFrame(err);
        }
    }

    private void handleRegister(Frame frame) {
        RegisterResult result = messagingService.register(frame.getClientId(), this);

        if (result.status() == RegisterResult.Status.OK) {
            this.clientId = frame.getClientId();
            sendFrame(Frame.of(Operations.REGISTER_OK));
        } else {
            Frame err = Frame.of(Operations.REGISTER_ERR);
            err.setCode(result.code());
            err.setReason(result.reason());
            sendFrame(err);
        }
    }

    private void handleSend(Frame frame) {
        if (clientId == null) {
            sendUnregisteredError();
            return;
        }
        SendResult result = messagingService.send(clientId, frame.getTo(), frame.getMsgId(), frame.getPayload());
        Frame ack = Frame.of(Operations.SEND_ACK);
        ack.setMsgId(frame.getMsgId());
        ack.setStatus(result.status().name());
        if (result.status() == SendResult.Status.REJECTED) {
            ack.setCode(result.code());
            ack.setReason(result.reason());
        }
        boolean sent = sendFrame(ack);
        if (!sent) {
            // SEND_ACK is a promise the sender relies on to know whether
            // at-least-once delivery will be attempted. If backpressure
            // means we can't even deliver that promise, the sender's view
            // of this session is unreliable — close rather than let them
            // believe a SEND that we couldn't confirm was ever handled.
            log.warn("Dropping connection for clientId={}: could not deliver SEND_ACK for msgId={}",
                    clientId, frame.getMsgId());
            close();
        }
    }

    private void handleAck(Frame frame) {
        if (clientId == null) {
            sendUnregisteredError();
            return;
        }
        if (isBlank(frame.getMsgId()) || isBlank(frame.getFrom())) {
            // Both are required to target one specific message unambiguously
            // (msgId alone is only unique per sender, not per recipient) — a
            // frame missing either would otherwise silently match zero rows
            // and look identical to a harmless stale/repeated ack.
            Frame err = Frame.of(Operations.ERROR);
            err.setCode("INVALID_ACK");
            err.setReason("ACK requires both msgId and from");
            sendFrame(err);
            return;
        }
        messagingService.ack(clientId, frame.getFrom(), frame.getMsgId());
        // ACK has no response frame.
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void sendUnregisteredError() {
        Frame err = Frame.of(Operations.ERROR);
        err.setCode("NOT_REGISTERED");
        err.setReason("must REGISTER before sending or acking");
        sendFrame(err);
    }

    /** Queues a message for the writer thread, translating it to a DELIVER {@link Frame}. */
    @Override
    public boolean deliver(Message message) {
        Frame frame = Frame.of(Operations.DELIVER);
        frame.setMsgId(message.getMsgId());
        frame.setFrom(message.getFrom());
        frame.setPayload(message.getPayload());
        return sendFrame(frame);
    }

    private boolean sendFrame(Frame frame) {
        if (closed.get()) {
            return false;
        }
        boolean offered = writeQueue.offer(frame);
        if (!offered) {
            log.warn("Write queue full for clientId={}, dropping frame op={} msgId={}",
                    clientId, frame.getOp(), frame.getMsgId());
        }
        return offered;
    }

    private void startWriterThread() {
        writerThread = Thread.ofVirtual().start(() -> {
            try (Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                while (!closed.get()) {
                    Frame frame = writeQueue.take();
                    codec.encode(writer, frame);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                log.debug("Writer stopped for clientId={}: {}", clientId, e.getMessage());
            } finally {
                close();
            }
        });
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (clientId != null) {
                messagingService.disconnect(clientId);
            }
            if (writerThread != null) {
                writerThread.interrupt();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // Ignore close errors.
            }
        }
    }
}
