package com.cisco.webex.domain.client;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of known clients, keyed by client id. Presence is
 * ephemeral (lost on restart) — this is not the durable message store,
 * see {@code MailboxRepository} for that.
 */
@Component
public class ClientDirectory {

    /** Business-level bounds this directory enforces. Framework-free — built by whoever constructs this directory. */
    public record Settings(int dedupeWindowSize) {}

    private final ConcurrentMap<String, Client> clients = new ConcurrentHashMap<>();
    private final int dedupeWindowSize;

    public ClientDirectory(Settings settings) {
        this.dedupeWindowSize = settings.dedupeWindowSize();
    }

    /** Returns the existing Client for this id, creating one if unknown. */
    public Client getOrCreate(String clientId) {
        return clients.computeIfAbsent(clientId, id -> new Client(id, dedupeWindowSize));
    }

    public Optional<Client> find(String clientId) {
        return Optional.ofNullable(clients.get(clientId));
    }

    public boolean exists(String clientId) {
        return clients.containsKey(clientId);
    }
}
