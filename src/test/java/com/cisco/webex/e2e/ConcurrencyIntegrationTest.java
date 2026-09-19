package com.cisco.webex.e2e;

import com.cisco.webex.broker.InMemoryMessageBroker;
import com.cisco.webex.config.MessagingProperties;
import com.cisco.webex.config.StorageProperties;
import com.cisco.webex.config.TransportProperties;
import com.cisco.webex.domain.client.ClientDirectory;
import com.cisco.webex.domain.messaging.MailboxRepository;
import com.cisco.webex.domain.messaging.MessagingServiceImpl;
import com.cisco.webex.orchestration.ClientConnection;
import com.cisco.webex.orchestration.QueueConsumer;
import com.cisco.webex.protocol.FrameCodec;
import com.cisco.webex.support.JpaTestSupport;
import com.cisco.webex.transport.TCPRelayServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the relay under concurrent load with a real socket transport
 * and a real (H2-backed) MailboxRepository — the behaviors that a mocked unit test
 * can't demonstrate: cross-client isolation, concurrent state correctness,
 * and bounded shutdown. Deterministic and timeout-bounded throughout;
 * no sleeps used for correctness, only for allowing the async QueueConsumer
 * to catch up, always followed by a bounded wait-loop rather than a fixed
 * guess.
 */
class ConcurrencyIntegrationTest {

    private AnnotationConfigApplicationContext jpaCtx;
    private TCPRelayServer server;
    private QueueConsumer consumer;

    @BeforeEach
    void startServer() {
        jpaCtx = JpaTestSupport.createContext();
        MailboxRepository mailbox = JpaTestSupport.mailbox(jpaCtx);

        TransportProperties transportProperties = new TransportProperties();
        transportProperties.setPort(0);
        transportProperties.setMaxActiveConnections(100);
        transportProperties.setMaxFrameBytes(70_000);

        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setMaxMailboxSize(50);

        MessagingProperties messagingProperties = new MessagingProperties();
        messagingProperties.setMaxPayloadBytes(4096);

        ClientDirectory clientDirectory = new ClientDirectory(
                new ClientDirectory.Settings(messagingProperties.getDedupeWindowSize()));
        InMemoryMessageBroker broker = new InMemoryMessageBroker();
        MessagingServiceImpl.Limits limits = new MessagingServiceImpl.Limits(
                messagingProperties.getMaxPayloadBytes(), storageProperties.getMaxMailboxSize());
        MessagingServiceImpl messagingService = new MessagingServiceImpl(clientDirectory, mailbox, broker, limits);

        FrameCodec codec = new FrameCodec(new ObjectMapper(), transportProperties);

        consumer = new QueueConsumer(broker, messagingService);
        consumer.start();

        server = new TCPRelayServer(transportProperties,
                socket -> new ClientConnection(socket, codec, messagingService,
                        transportProperties.getWriteQueueCapacity()));
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server.isRunning()) {
            server.stop();
        }
        consumer.stop();
        jpaCtx.close();
    }

    private TestClient connect() throws IOException {
        Socket socket = new Socket("localhost", server.getLocalPort());
        socket.setSoTimeout(4000);
        return new TestClient(socket);
    }

    // ---- 1. many concurrent sender/recipient pairs: no cross-delivery, FIFO per pair ----

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void concurrentSendersToDistinctRecipientsPreserveFifoWithNoCrossDelivery() throws Exception {
        int pairs = 4;
        int messagesPerPair = 5;

        List<TestClient> recipients = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            TestClient recipient = connect();
            recipient.send("{\"op\":\"REGISTER\",\"clientId\":\"recipient" + i + "\"}");
            assertThat(recipient.readLine()).contains("REGISTER_OK");
            recipients.add(recipient);
        }

        ExecutorService senders = Executors.newFixedThreadPool(pairs);
        CountDownLatch ready = new CountDownLatch(pairs);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < pairs; i++) {
                int recipientIndex = i;
                senders.submit(() -> {
                    try (TestClient sender = connect()) {
                        sender.send("{\"op\":\"REGISTER\",\"clientId\":\"sender" + recipientIndex + "\"}");
                        sender.readLine(); // REGISTER_OK
                        ready.countDown();
                        go.await();
                        for (int m = 0; m < messagesPerPair; m++) {
                            sender.send(String.format(
                                    "{\"op\":\"SEND\",\"msgId\":\"s%d-m%d\",\"to\":\"recipient%d\",\"payload\":\"p%d\"}",
                                    recipientIndex, m, recipientIndex, m));
                            sender.readLine(); // SEND_ACK
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown(); // release all senders at once, maximizing overlap

            for (int i = 0; i < pairs; i++) {
                TestClient recipient = recipients.get(i);
                for (int m = 0; m < messagesPerPair; m++) {
                    String line = recipient.readLine();
                    assertThat(line).contains("DELIVER");
                    assertThat(line).contains("\"msgId\":\"s" + i + "-m" + m + "\"");
                    recipient.send("{\"op\":\"ACK\",\"msgId\":\"s" + i + "-m" + m + "\",\"from\":\"sender" + i + "\"}");
                }
            }
        } finally {
            senders.shutdown();
            senders.awaitTermination(5, TimeUnit.SECONDS);
            for (TestClient recipient : recipients) {
                recipient.close();
            }
        }
    }

    // ---- 2. a malformed/stalled client must not block unrelated clients ----

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void malformedClientDoesNotBlockUnrelatedClients() throws Exception {
        // Stalled client: connects, registers, then never sends or reads again.
        TestClient stalled = connect();
        stalled.send("{\"op\":\"REGISTER\",\"clientId\":\"stalled\"}");
        assertThat(stalled.readLine()).contains("REGISTER_OK");

        // Malformed client: sends garbage that trips ProtocolException.
        try (TestClient malformed = connect()) {
            malformed.send("{not valid json");
            String reply = malformed.readLine();
            assertThat(reply == null || reply.contains("ERROR")).isTrue();
        }

        long start = System.currentTimeMillis();
        try (TestClient alice = connect(); TestClient bob = connect()) {
            alice.send("{\"op\":\"REGISTER\",\"clientId\":\"alice\"}");
            assertThat(alice.readLine()).contains("REGISTER_OK");
            bob.send("{\"op\":\"REGISTER\",\"clientId\":\"bob\"}");
            assertThat(bob.readLine()).contains("REGISTER_OK");

            alice.send("{\"op\":\"SEND\",\"msgId\":\"m1\",\"to\":\"bob\",\"payload\":\"hi\"}");
            assertThat(alice.readLine()).contains("SEND_ACK");
            assertThat(bob.readLine()).contains("DELIVER");
        }
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(elapsedMs).isLessThan(3000);
        stalled.close();
    }

    // ---- 3. reconnect with pending messages, under concurrent unrelated traffic ----

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void reconnectRedeliversPendingMessagesUnderConcurrentTraffic() throws Exception {
        try (TestClient bob = connect()) {
            bob.send("{\"op\":\"REGISTER\",\"clientId\":\"bob\"}");
            assertThat(bob.readLine()).contains("REGISTER_OK");
        } // bob goes offline, remains known

        ExecutorService background = Executors.newFixedThreadPool(3);
        CountDownLatch stop = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) {
            int idx = i;
            background.submit(() -> {
                try (TestClient c = connect()) {
                    c.send("{\"op\":\"REGISTER\",\"clientId\":\"noise" + idx + "\"}");
                    c.readLine();
                    while (stop.getCount() > 0) {
                        c.send("{\"op\":\"SEND\",\"msgId\":\"n" + idx + "-" + System.nanoTime()
                                + "\",\"to\":\"noise" + idx + "\",\"payload\":\"x\"}");
                        c.readLine();
                        Thread.sleep(20);
                    }
                } catch (Exception ignored) {
                    // Background noise; connection tear-down races with test end are expected.
                }
            });
        }

        try (TestClient alice = connect()) {
            alice.send("{\"op\":\"REGISTER\",\"clientId\":\"alice\"}");
            assertThat(alice.readLine()).contains("REGISTER_OK");

            for (int m = 0; m < 3; m++) {
                alice.send("{\"op\":\"SEND\",\"msgId\":\"m" + m + "\",\"to\":\"bob\",\"payload\":\"p" + m + "\"}");
                assertThat(alice.readLine()).contains("SEND_ACK");
            }
        }

        try (TestClient bobAgain = connect()) {
            bobAgain.send("{\"op\":\"REGISTER\",\"clientId\":\"bob\"}");
            assertThat(bobAgain.readLine()).contains("REGISTER_OK");

            for (int m = 0; m < 3; m++) {
                String line = bobAgain.readLine();
                assertThat(line).contains("DELIVER");
                assertThat(line).contains("\"msgId\":\"m" + m + "\"");
                bobAgain.send("{\"op\":\"ACK\",\"msgId\":\"m" + m + "\",\"from\":\"alice\"}");
            }
        } finally {
            stop.countDown();
            background.shutdown();
            background.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ---- 4. predictable shutdown while connections are active ----

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void serverStopReturnsQuicklyAndClosesActiveConnections() throws Exception {
        List<TestClient> clients = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TestClient c = connect();
            c.send("{\"op\":\"REGISTER\",\"clientId\":\"client" + i + "\"}");
            assertThat(c.readLine()).contains("REGISTER_OK");
            clients.add(c);
        }

        long start = System.currentTimeMillis();
        server.stop();
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(elapsedMs).isLessThan(3000);
        assertThat(server.isRunning()).isFalse();

        for (TestClient c : clients) {
            String line = c.readLine(); // expect EOF (null) since the server closed the socket
            assertThat(line).isNull();
            c.close();
        }
    }

    static class TestClient implements AutoCloseable {
        private final Socket socket;
        private final PrintWriter out;
        private final BufferedReader in;

        TestClient(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        void send(String line) {
            out.println(line);
        }

        String readLine() throws IOException {
            return in.readLine();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
