# query-and-mutation

Records a real subscription and mutation through the sync WebSocket, so the
Kotlin codecs are proven against captured backend traffic rather than only
against each other.

Unlike `connect-handshake` (hand-authored frames), this scenario drives the
**real convex-js client**: it subscribes to `messages:list`, runs
`messages:send`, and unsubscribes, while a `ws`-based recorder captures both
directions.

## Sequence

```
client → Connect
client → ModifyQuerySet { Add messages:list }
server ← Transition { QueryUpdated [], querySet 0→1 }
client → Mutation messages:send { body: "hello" }
server ← MutationResponse { success, result 5.0 }
server ← Transition { QueryUpdated [ one doc ], querySet stays 1 }
client → ModifyQuerySet { Remove }
server ← Transition { QueryRemoved, querySet 1→2 }
```

The collection is cleared over HTTP before recording (a separate
`ConvexHttpClient`), so the recorded list value does not depend on documents
left by earlier runs.

## Protocol facts this fixture pins down

- **`serverTs` is a plain JSON number**, not base64, even though it can exceed
  2^53. `StateVersion.ts` and `MutationResponse.ts` **are** base64 little-endian.
  The Kotlin decoder accepts both forms; the encoder emits the number for
  `serverTs` to match the wire.
- **`args` lives inside each `QuerySetModification`**, not at the message top
  level, in the traffic convex-js actually sends.
- The convex-js `Connect` frame orders keys as
  `connectionCount, lastCloseReason, clientTs, type, sessionId` — different from
  the upstream Rust struct order. Key order is not significant to the backend;
  the decoder is order-insensitive.

## Normalization

Run-variant values are replaced with **fixed, correctly-typed constants** so the
fixtures are byte-stable and still decode without substitution:

| Field | Normalized to |
| --- | --- |
| `sessionId` | `00000000-0000-4000-8000-000000000000` |
| `clientTs`, `serverTs`, `clientClockSkew` | `0` |
| `ts` (base64 in StateVersion / MutationResponse) | `AAAAAAAAAAA=` (zero) |
| `journal` | `null` |
| `_creationTime` | `0` |
| `_id` | `0000000000000000000000000000000000000000` |

Recordings are verified byte-stable across runs.

## Verification

`convex-core`'s `ProtocolFixtureTest` (JVM) decodes every frame in this
directory with `ClientMessageJson` / `ServerMessageJson`. It runs as part of
`./gradlew check`.

## Backend pin

`ghcr.io/get-convex/convex-backend:d2ca8533c52e2dbd8e55e20181988973dcbabe41`
(Convex `d2ca853`, 2026-10-01).

## Re-record

```bash
docker compose -f conformance/docker-compose.yml up -d --force-recreate
docker compose -f conformance/docker-compose.yml exec backend ./generate_admin_key.sh
cd conformance/harness && npm ci
node record-subscription.mjs --url http://127.0.0.1:3210 --admin-key <key>
docker compose -f conformance/docker-compose.yml down -v
```

`--force-recreate` matters: a container created while the ports were busy can
start without host port bindings, which makes the deployment unreachable.
