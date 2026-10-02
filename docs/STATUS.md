# Status

A frank inventory of what is implemented, how it is verified, and what is
deliberately not done yet. The project plan's steps 1–10 are complete as slices;
this file records the seams that remain.

## Implemented

| Area | What exists |
| --- | --- |
| `convex-core` values | `ConvexValue` sealed hierarchy and the `ConvexJson` codec: 64-bit integers and non-finite floats as tagged little-endian base64, `$set`/`$map` rejected, key-presence handling |
| `convex-core` protocol | `ClientMessage`/`ServerMessage` sealed hierarchies with encoders/decoders, scalar types, `StateVersion`, `StateModification`, `ConvexResult` |
| `convex-core` sync | `LocalSyncState` (subscription intent → messages, id reuse, versioning, resend) and `RemoteQuerySet` (contiguous transitions, results) |
| `convex-core` functions | `ConvexFunction` descriptors and a closed `ConvexValidator` model |
| `convex-client` | `SyncProtocol` seam, `ConvexSyncClient` (outgoing queue, receive loop, subscribe/mutate/action, `StateFlow` results), Ktor transport, `syncUrl`, auth fetch, manual reconnect |
| `convex-auth` | JWT parsing and claim extraction; RS256/ES256 verification on JVM/Android |
| `convex-storage` | `generateUploadUrl`, `upload`, `uploadFile`, `download`, `fileUrl` over HTTP |
| `convex-compose` | `QueryState<T>`, `ConvexDecoder<T>`, `QueryController`, `rememberQuery` |
| `convex-codegen` | `ApiSpecParser` and `KotlinSourceGenerator` |
| `tools/parity` | Manifest validator, upstream test discovery, coverage test, `--emit-missing` |
| `examples/chat` | Compose Desktop chat app |

## Verification

- `./gradlew checkAll` (or `build`) runs formatting, Detekt, API checks,
  coverage, and every module's tests. All green.
- Conformance fixtures: `connect-handshake` and `query-and-mutation`, recorded
  from the pinned backend and replayed by tests. CI re-records and fails on
  drift.
- Parity: `ParityCoverageTest` requires every upstream `convex-rs` test to be
  accounted for in `parity.yaml` (60 tests; 6 `ported`, the rest `planned`).

## Known gaps

Ordered roughly by how likely they are to matter.

1. **Optimistic updates.** Deferred by decision; `RemoteQuerySet` is the server
   truth and mutations do not predict query changes (upstream is also a stub).
2. **JWT verification on Apple targets.** `verifySignature` throws
   `NotImplementedError` on iOS; tokens are forwarded to the backend, which
   verifies them. JVM and Android verify locally.
3. **Pagination / journal.** `Query.journal` is carried and unused.
4. **Storage against a live backend.** The storage client is unit-tested with a
   mock engine; its endpoints have not been exercised against the pinned
   backend, so the exact paths/auth scheme are not yet fixture-proven.
5. **Codegen wiring.** Descriptors are generated but the client still takes
   string paths; nothing yet maps a `ConvexFunction` to a typed call.
6. **Parity depth.** Coverage enforcement is real, but most entries are
   `planned`; porting them is ongoing work.

Closed during refinement: automatic reconnection with exponential backoff,
mutation/action call timeouts, `TransitionChunk` reassembly, and surfacing
server `AuthError`s (now a `SharedFlow<String>`).

## Upstream pins

- `convex-backend` `d2ca8533c52e2dbd8e55e20181988973dcbabe41`
- `convex-rs` `a4d04a9f4990078693760c2a08d0032323aa7f1b` (the submodule)
