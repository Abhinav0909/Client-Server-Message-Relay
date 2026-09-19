# Webex message relay

A small TCP client/server message relay: clients register with an id, send addressed messages to each
other, and acknowledge delivery. Offline messages are retained (bounded) and redelivered on reconnect.

See [APPROACH.md](APPROACH.md) for design, protocol, trade-offs, and an end-to-end sequence diagram.

## Prerequisites

- JDK 21+
- No local Maven needed — repo includes the wrapper (`mvnw` / `mvnw.cmd`)

## Build

```bash
./mvnw clean package
```

Produces `target/webex-0.0.1-SNAPSHOT.jar`.

## Run

```bash
java -jar target/webex-0.0.1-SNAPSHOT.jar
```

Or without building a jar first:

```bash
./mvnw spring-boot:run
```

- Listens on TCP port `5555` by default; logs the bound port on startup.
- To manually test it, no `nc`/`telnet` needed (neither ships with Windows by default):
  ```powershell
  ./scripts/test-client.ps1
  ```
  Type a JSON frame and press Enter, e.g. `{"op":"REGISTER","clientId":"alice"}` — see APPROACH.md for
  the protocol. Uses only built-in .NET classes, nothing to install.

## Test

```bash
./mvnw test
```

- Unit tests: mocked collaborators (`Client`, `ClientDirectory`, `MessagingServiceImpl`, `FrameCodec`,
  `InMemoryMessageBroker`, `QueueConsumer`, `ClientConnection`).
- One end-to-end test (`ConcurrencyIntegrationTest`): real TCP server + real H2-backed mailbox, under
  concurrent load.
- All tests deterministic and `@Timeout`-bounded — no fixed-duration sleeps for correctness, only
  bounded wait/poll where async work needs to catch up.

## Configuration

All under `src/main/resources/application.properties`:

| Property | Default | Controls |
|---|---|---|
| `relay.transport.port` | `5555` | Listening port (`0` = OS picks a free port — used by tests) |
| `relay.transport.max-active-connections` | `1000` | Sockets rejected once this many are open |
| `relay.transport.max-frame-bytes` | `70000` | Max wire-frame size (actual UTF-8 bytes) |
| `relay.transport.write-queue-capacity` | `256` | Per-connection outbound frame buffer before backpressure |
| `relay.storage.max-mailbox-size` | `100` | Max rows (queued + unacked) per recipient |
| `relay.messaging.max-payload-bytes` | `65536` | Max `SEND` payload size |
| `relay.messaging.dedupe-window-size` | `1000` | Per-recipient window of seen `(sender, msgId)` pairs — msgId is unique per sender, not globally |
| `relay.broker.max-queue-size` | `10000` | Max in-flight (published, not yet delivered) messages |

Override via `-D` system properties, env vars (`RELAY_TRANSPORT_PORT=0`), or
`application-<profile>.properties`.

## Server lifecycle

- `TCPRelayServer` and `QueueConsumer` are Spring `SmartLifecycle` beans — start automatically with the
  application context, stop automatically on `SIGTERM`/context shutdown.
- `TCPRelayServer.stop()`: closes the listening socket and every active connection, then joins the
  accept-loop thread (2s bound).
- `QueueConsumer.stop()`: joins its poll-loop thread (2s bound).
- Shutdown only returns once work has actually stopped, not merely been signalled to — see
  [docs/protocol-details.md](docs/protocol-details.md#shutdown).

## Storage

- H2 in-memory (`jdbc:h2:mem:webex`) via Spring Data JPA.
- Schema generated from the entity (`spring.jpa.hibernate.ddl-auto=update`).
- Explicit, documented trade-off — see APPROACH.md's Design choices section for why and its limitations.
