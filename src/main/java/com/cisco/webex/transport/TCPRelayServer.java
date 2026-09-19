package com.cisco.webex.transport;

import com.cisco.webex.config.TransportProperties;
import com.cisco.webex.orchestration.ClientConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Accepts TCP sockets and creates a {@link ClientConnection} per client,
 * bounded by {@link TransportProperties#getMaxActiveConnections()}. A
 * connection that never registers, or a slow/malformed one, must not block
 * accepting or serving unrelated clients — each connection runs on its own
 * thread.
 */
@Component
public class TCPRelayServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TCPRelayServer.class);

    private final int port;
    private final int maxActiveConnections;
    private final Function<Socket, ClientConnection> connectionFactory;

    private final Set<ClientConnection> activeConnections = new CopyOnWriteArraySet<>();
    private final Map<ClientConnection, Thread> connectionThreads = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public TCPRelayServer(TransportProperties transportProperties,
                           Function<Socket, ClientConnection> connectionFactory) {
        this.port = transportProperties.getPort();
        this.maxActiveConnections = transportProperties.getMaxActiveConnections();
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            serverSocket = new ServerSocket(port);
            log.info("Relay TCP server listening on port {}", serverSocket.getLocalPort());
        } catch (IOException e) {
            running.set(false);
            throw new IllegalStateException("Failed to bind relay server on port " + port, e);
        }

        acceptThread = Thread.ofVirtual().name("relay-accept-loop").start(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("Accept loop error: {}", e.getMessage());
                }
                break;
            }

            if (activeConnections.size() >= maxActiveConnections) {
                log.warn("Rejecting connection from {}: max active connections ({}) reached",
                        socket.getRemoteSocketAddress(), maxActiveConnections);
                closeQuietly(socket);
                continue;
            }

            ClientConnection connection = connectionFactory.apply(socket);
            activeConnections.add(connection);

            Thread handlerThread = Thread.ofVirtual().start(() -> {
                try {
                    connection.run();
                } finally {
                    activeConnections.remove(connection);
                    connectionThreads.remove(connection);
                }
            });
            connectionThreads.put(connection, handlerThread);
        }
    }

    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Ignore close errors.
        }
    }

    /** Stops accepting new connections and closes active client sockets. Must return within a bounded time. */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping relay TCP server");

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Closing the socket interrupts accept().
            }
        }

        for (ClientConnection connection : activeConnections) {
            connection.close();
        }
        activeConnections.clear();

        // Closing each socket above unblocks that connection's read loop,
        // but doesn't wait for its handler thread to actually finish an
        // in-flight request. Without this, a caller that assumes stop()
        // means "no longer running" can race a still-executing handler —
        // e.g. one still mid-write against a datasource the caller is
        // about to tear down right after stop() returns.
        joinConnectionThreads(2000);

        if (acceptThread != null) {
            try {
                acceptThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Joins all per-connection handler threads, sharing one overall deadline rather than one bound per thread. */
    private void joinConnectionThreads(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Thread thread : connectionThreads.values()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            try {
                thread.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Ensures shutdown happens before other beans are torn down. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** Returns the actual port in use, including when the OS picks a free port (configured port 0). */
    public int getLocalPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }
}
