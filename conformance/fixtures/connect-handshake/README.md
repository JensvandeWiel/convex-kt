# connect-handshake

Records the first WebSocket exchange that establishes a Convex sync session, so
the Kotlin client can be proven byte-compatible with the pinned backend.

This is **step 3** of the project plan and the first input to the
`capture-conformance` workflow.

## What the scenario does

1. `convex deploy` pushes `harness/project` to the local backend (self-hosted
   targeting via `CONVEX_SELF_HOSTED_URL` / `CONVEX_SELF_HOSTED_ADMIN_KEY`), which
   creates a client deployment.
2. A raw WebSocket is opened to `<deployment>/api/sync` and recording starts
   **before** any frame is sent.
3. The recorder sends one `Connect` frame, waits for the first server frame, then
   closes.

It is deliberately one interaction so a failed assertion localizes to the
handshake.

## Recorded exchange

A valid `Connect` is followed immediately by the server's keepalive `Ping`:

```
client → {"type":"Connect","sessionId":"<SESSION_ID>","connectionCount":0,
          "lastCloseReason":"InitialConnect","maxObservedTimestamp":null,"clientTs":null}
server → {"type":"Ping"}
```

## Protocol facts this fixture pins down

These were discovered by driving the real backend, and each was previously a
guess that the server rejected. They are exactly what the Kotlin port must match:

- The JSON key is **`sessionId`** (camelCase). The Rust source declares
  `session_id`, so the serializer renames it. Sending `session_id` fails with
  `missing field 'sessionId'`.
- `sessionId` must be a **UUID**. A short opaque token fails with
  `invalid length: found 6`.
- `connectionCount` is **required**. Omitting it fails with
  `missing field 'connectionCount'`.
- The `Connected` message described in `convex-rs`'s `ServerMessage` enum is not
  what this backend sends first for a fresh session; the first server frame is a
  `Ping`. Do not assume a `Connected` reply exists in the wire protocol just
  because it exists in the type model.

References: `get-convex/convex-rs` → `sync_types/src/types/mod.rs`
(`ClientMessage::Connect`, `ServerMessage`).

## Fixtures

| File | Contents |
| --- | --- |
| `client-to-server.ndjson` | every frame the client sent, in order |
| `server-to-client.ndjson` | every frame the server sent, in order |
| `observed.json` | concrete values seen during the run (not consumed by tests) |

Each line is `{"header": {...}, "data": "<raw frame text>"}`. The raw text is
authoritative because Convex encodes 64-bit integers as JSON numbers that a
JavaScript reader would corrupt. The Kotlin conformance test must parse the raw
text with a precision-preserving codec.

Recordings are verified byte-stable across runs: re-running the recorder
produces identical file hashes.

## Non-deterministic values

Replaced with placeholders in the committed fixtures. The vocabulary lives in
`harness/placeholders.mjs`:

| Placeholder | Replaces | Why it varies |
| --- | --- | --- |
| `<SESSION_ID>` | `Connect.sessionId` | a fresh UUID per run |
| `<TOKEN>` | the admin key | deployment-specific secret |
| `<TIMESTAMP_U64>` | 64-bit timestamps, when present | server clock (unused here: the first frame is `Ping`) |

Structural bytes — field order, string escapes, and the base64 payload encoding
of 64-bit integers — are **not** normalized. That is the point of the fixture.

## Backend pin

- `ghcr.io/get-convex/convex-backend:d2ca8533c52e2dbd8e55e20181988973dcbabe41`
  (Convex `d2ca853`, 2026-10-01).
- Image digest:
  `sha256:0878dd7d51513b9bc51bc656ee3fcb229e166b93bd0abc42654e1252fd93a3e3`.

## Re-record

```bash
docker compose -f conformance/docker-compose.yml up -d
docker compose -f conformance/docker-compose.yml exec backend ./generate_admin_key.sh
cd conformance/harness && npm ci
node record-handshake.mjs --url http://127.0.0.1:3210 --admin-key <key>
docker compose -f conformance/docker-compose.yml down -v
```

Never hand-edit a fixture. Re-record it and update this README if the protocol
changed.
