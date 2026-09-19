package com.cisco.webex.orchestration;

import com.cisco.webex.config.TransportProperties;
import com.cisco.webex.domain.client.Client;
import com.cisco.webex.domain.client.ClientChannel;
import com.cisco.webex.domain.messaging.MessagingService;
import com.cisco.webex.domain.messaging.RegisterResult;
import com.cisco.webex.domain.messaging.SendResult;
import com.cisco.webex.protocol.FrameCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Drives ClientConnection over a real loopback socket pair rather than
 * mocking the socket — its constructor takes a live Socket, and dispatch
 * (REGISTER/SEND/ACK -&gt; MessagingService) is only reachable through the
 * real read loop. MessagingService itself is mocked so only dispatch is
 * under test, not business logic.
 */
class ClientConnectionTest {

    private ServerSocket serverSocket;
    private Socket clientSide;
    private Socket serverSide;
    private MessagingService messagingService;
    private FrameCodec codec;
    private PrintWriter out;
    private BufferedReader in;
    private Thread connectionThread;

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);
        clientSide = new Socket("localhost", serverSocket.getLocalPort());
        clientSide.setSoTimeout(3000);
        serverSide = serverSocket.accept();

        messagingService = mock(MessagingService.class);
        TransportProperties transportProperties = new TransportProperties();
        transportProperties.setMaxFrameBytes(70_000);
        codec = new FrameCodec(new ObjectMapper(), transportProperties);

        out = new PrintWriter(clientSide.getOutputStream(), true, StandardCharsets.UTF_8);
        in = new BufferedReader(new InputStreamReader(clientSide.getInputStream(), StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        clientSide.close();
        if (connectionThread != null) {
            connectionThread.join(2000);
        }
        serverSocket.close();
    }

    private void startConnection() {
        ClientConnection connection = new ClientConnection(serverSide, codec, messagingService, 256);
        connectionThread = Thread.ofVirtual().start(connection::run);
    }

    private void registerAs(String clientId) throws IOException {
        Client fakeSession = mock(Client.class);
        when(messagingService.register(eq(clientId), any(ClientChannel.class)))
                .thenReturn(RegisterResult.ok(fakeSession));
        startConnection();
        out.println("{\"op\":\"REGISTER\",\"clientId\":\"" + clientId + "\"}");
        assertThat(in.readLine()).contains("REGISTER_OK");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void registerFrameDispatchesToMessagingServiceAndRepliesOk() throws IOException {
        registerAs("alice");
        verify(messagingService).register(eq("alice"), any(ClientChannel.class));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendFrameDispatchesToMessagingServiceAndRepliesWithAck() throws IOException {
        registerAs("alice");
        when(messagingService.send("alice", "bob", "m1", "hi")).thenReturn(SendResult.accepted());

        out.println("{\"op\":\"SEND\",\"msgId\":\"m1\",\"to\":\"bob\",\"payload\":\"hi\"}");
        String reply = in.readLine();

        assertThat(reply).contains("SEND_ACK");
        verify(messagingService).send("alice", "bob", "m1", "hi");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void ackFrameDispatchesToMessagingServiceWithNoResponseFrame() throws IOException {
        registerAs("alice");
        when(messagingService.send("alice", "bob", "m2", "hi")).thenReturn(SendResult.accepted());

        out.println("{\"op\":\"ACK\",\"msgId\":\"m1\",\"from\":\"bob\"}");
        // ACK has no response frame — confirm the next frame read is the
        // response to a SUBSEQUENT request, not a stray reply to the ACK.
        out.println("{\"op\":\"SEND\",\"msgId\":\"m2\",\"to\":\"bob\",\"payload\":\"hi\"}");
        String reply = in.readLine();

        assertThat(reply).contains("SEND_ACK");
        verify(messagingService).ack("alice", "bob", "m1");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void ackMissingFromIsRejectedWithoutCallingMessagingService() throws IOException {
        registerAs("alice");

        out.println("{\"op\":\"ACK\",\"msgId\":\"m1\"}");
        String reply = in.readLine();

        assertThat(reply).contains("ERROR").contains("INVALID_ACK");
        verify(messagingService, never()).ack(any(), any(), any());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void ackMissingMsgIdIsRejectedWithoutCallingMessagingService() throws IOException {
        registerAs("alice");

        out.println("{\"op\":\"ACK\",\"from\":\"bob\"}");
        String reply = in.readLine();

        assertThat(reply).contains("ERROR").contains("INVALID_ACK");
        verify(messagingService, never()).ack(any(), any(), any());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendBeforeRegisterIsRejectedWithoutCallingMessagingService() throws IOException {
        startConnection();

        out.println("{\"op\":\"SEND\",\"msgId\":\"m1\",\"to\":\"bob\",\"payload\":\"hi\"}");
        String reply = in.readLine();

        assertThat(reply).contains("ERROR").contains("NOT_REGISTERED");
        verifyNoInteractions(messagingService);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void serverToClientOnlyOpIsRejectedWithoutCallingMessagingService() throws IOException {
        startConnection();

        out.println("{\"op\":\"DELIVER\",\"msgId\":\"m1\"}");
        String reply = in.readLine();

        assertThat(reply).contains("ERROR").contains("UNSUPPORTED_OP");
        verifyNoInteractions(messagingService);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void closingTheSocketDisconnectsTheRegisteredClient() throws IOException, InterruptedException {
        registerAs("alice");

        clientSide.close();
        connectionThread.join(2000);

        verify(messagingService).disconnect("alice");
    }
}
