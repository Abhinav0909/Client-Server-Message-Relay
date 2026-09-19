# Protocol details

Implementation-level detail for the wire protocol summarized in [APPROACH.md](../APPROACH.md#protocol).
Read this if you're implementing a client, debugging a frame, or reviewing edge cases — otherwise the
summary in APPROACH.md is enough.

## Format

- One JSON object per line, separated by `\n`. No binary encoding, no headers.
  ```
  {"op":"REGISTER","clientId":"alice"}
  {"op":"SEND","msgId":"m1","to":"bob","payload":"hi"}
  ```
- The socket only ever sees these bytes. `Frame` (our internal Java representation, built by
  `FrameCodec` via Jackson) never crosses the wire — only the JSON text it was parsed from or serialized
  to.
- Each line is bounded by `relay.transport.max-frame-bytes` (UTF-8 bytes).
- `Frame` has a field for every possible op; a given message only fills in the fields its own op needs.
- Manually testable with `scripts/test-client.ps1` (built-in .NET `TcpClient`, no install needed).

## Operations

| Op | Direction | Purpose |
|---|---|---|
| `REGISTER` | client → server | register or reconnect with an id |
| `SEND` | client → server | send a message to another client |
| `ACK` | client → server | acknowledge a delivered message |
| `REGISTER_OK` / `REGISTER_ERR` | server → client | registration result |
| `SEND_ACK` | server → client | accept/reject a send |
| `DELIVER` | server → client | deliver a message |
| `ERROR` | server → client | protocol or internal error |

A client sending a server-only op → `ERROR{UNSUPPORTED_OP}`; connection stays open.

## Examples, one JSON line each

```json
{"op":"REGISTER","clientId":"alice"}
{"op":"REGISTER_OK"}
{"op":"REGISTER_ERR","code":"INVALID_CLIENT_ID","reason":"clientId must be non-empty"}

{"op":"SEND","msgId":"m1","to":"bob","payload":"hello bob"}
{"op":"SEND_ACK","msgId":"m1","status":"ACCEPTED"}
{"op":"SEND_ACK","msgId":"m1","status":"REJECTED","code":"UNKNOWN_RECIPIENT","reason":"no such client: bob"}

{"op":"DELIVER","msgId":"m1","from":"alice","payload":"hello bob"}
{"op":"ACK","msgId":"m1","from":"alice"}

{"op":"ERROR","code":"NOT_REGISTERED","reason":"must REGISTER before sending or acking"}
```

`REGISTER`/`SEND`/`ACK` are the only ones a client ever writes; the rest are always read.

## Connection lifecycle

- Connect → `REGISTER{clientId}` → `REGISTER_OK`; any pending `UNACKED`/`QUEUED` messages redeliver
  immediately, same call.
- `SEND`/`ACK` before `REGISTER` → `ERROR{NOT_REGISTERED}`.
- Socket drops → `disconnect`; identity and mailbox untouched, only presence changes.
- Re-register with the same id from a new socket → evicts (closes) the previous socket.

## Bounds and error reporting

| Bound | Property | Enforced in | Reported as |
|---|---|---|---|
| Payload size | `relay.messaging.max-payload-bytes` | `send()`, before publish | `SEND_ACK{REJECTED, PAYLOAD_TOO_LARGE}` |
| Mailbox size | `relay.storage.max-mailbox-size` | `send()`, via `countByTo` — synchronous | `SEND_ACK{REJECTED, MAILBOX_FULL}` |
| Active connections | `relay.transport.max-active-connections` | `TCPRelayServer.acceptLoop`, before connection object exists | connection refused at socket level |
| Frame size | `relay.transport.max-frame-bytes` | `FrameCodec.decode` (UTF-8 bytes) | `ERROR{PROTOCOL_ERROR}`, connection closes |
| Broker queue depth | `relay.broker.max-queue-size` | `send()`, checked synchronously | `SEND_ACK{REJECTED, BROKER_UNAVAILABLE}` |
| Write buffer per connection | `relay.transport.write-queue-capacity` | `ClientConnection.sendFrame` | if `SEND_ACK` itself can't be queued, connection closes |
| Invalid `clientId`/`to`/`msgId` | — | `MessagingServiceImpl` | `REGISTER_ERR` / `SEND_ACK{REJECTED}` with a code (`INVALID_CLIENT_ID`, `INVALID_RECIPIENT`, `INVALID_MSG_ID`) |
| `ACK` missing `msgId` or `from` | — | `ClientConnection.handleAck`, before calling `MessagingService` | `ERROR{INVALID_ACK}`, connection stays open |
| Unexpected internal error mid-request | — | `ClientConnection.handleFrame` catch-all | `ERROR{INTERNAL_ERROR}`, connection stays open |

- Mailbox and broker checks run synchronously inside `send()`, so `ACCEPTED` is a real promise:
  at-least-once delivery is guaranteed to be attempted.
- Broker-capacity rejection does **not** mark the msgId seen — a client retry is treated as new, not
  swallowed by dedupe.
- Duplicate-`SEND` detection and `ACK` matching are both scoped by `(senderId, msgId)`, not `msgId` alone
  — `msgId` is only required to be unique within one sender's own messages to a given recipient. `ACK`
  carries `from` for exactly this reason: without it, two different senders reusing the same `msgId` for
  the same recipient could have one sender's `ACK` remove the other's still-unacknowledged message.

## Shutdown

- `TCPRelayServer` and `QueueConsumer` are `SmartLifecycle` beans — `stop()` is synchronous and bounded
  on both.
- `TCPRelayServer.stop()`: closes the listening socket, closes every active connection, joins every
  connection's handler thread against one shared 2s deadline, then joins the accept thread (2s).
- `QueueConsumer.stop()`: joins its poll thread (2s).
- Both mean `stop()` returning implies the work has actually finished, not just been signalled to stop —
  verified by `ConcurrencyIntegrationTest.serverStopReturnsQuicklyAndClosesActiveConnections`.
- See the [shutdown sequence diagram](reference/flow-shutdown.svg).
