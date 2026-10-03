# Status

A frank inventory of what is implemented, how it is verified, and what is
deliberately not done yet. The project plan's steps 1–10 are complete as slices;
this file records the seams that remain.

## Implemented

| Area | What exists |
| --- | --- |
| `convex-core` values | `ConvexValue` sealed hierarchy and the `ConvexJson` codec: 64-bit integers and non-finite floats as tagged little-endian base64, `$set`/`$map` rejected, key-presence handling; `ConvexValueEncoder`/`Decoder`/`Serializer` for typed argument and result round-trips that preserve `Int64` vs `Float64` |
| `convex-core` protocol | `ClientMessage`/`ServerMessage` sealed hierarchies with encoders/decoders, scalar types, `StateVersion`, `StateModification`, `ConvexResult` |
| `convex-core` sync | `LocalSyncState` (subscription intent → messages, id reuse, versioning, resend) and `RemoteQuerySet` (contiguous transitions, results) |
| `convex-core` functions | `ConvexFunction` descriptors, a closed `ConvexValidator` model, and the typed `ConvexQuery`/`ConvexMutation`/`ConvexAction` descriptors with argument encoding |
| `convex-core` errors | `ConvexError` and `ConvexResult.orThrow()`, the typed-call failure contract |
| `convex-client` | `SyncProtocol` seam, `ConvexSyncClient` (outgoing queue, receive loop, subscribe/mutate/action, `StateFlow` results), typed `subscribe`/`mutate`/`action` overloads, Ktor transport, `syncUrl`, auth fetch, manual reconnect |
| `convex-auth` | JWT parsing and claim extraction; RS256/ES256 verification on JVM/Android |
| `convex-storage` | `generateUploadUrl`, `upload`, `uploadFile`, `download`, `fileUrl` over HTTP |
| `convex-compose` | `QueryState<T>`, `ConvexDecoder<T>`, `QueryController`, `rememberQuery` (path, descriptor, and typed descriptor overloads) |
| `convex-codegen` | `ApiSpecParser`, `KotlinSourceGenerator` (nested `Api` object with `Input`/`Request`/result classes and typed descriptors), and the `CodegenCli` entry point |
| `tools/parity` | Manifest validator, upstream test discovery, coverage test, `--emit-missing` |
| `examples/chat` | Compose Desktop chat app |

## Verification

- `./gradlew checkAll` (or `build`) runs formatting, Detekt, API checks,
  coverage, and every module's tests. All green.
- Conformance fixtures: `connect-handshake`, `query-and-mutation`,
  `storage`, and `typed-api` (the real backend apiSpec), recorded from the
  pinned backend and replayed by tests. CI re-records and fails on drift.
- Typed calls: the committed `typed-api` fixture is generated into
  `convex-client`'s test sources and exercised by `TypedCallsTest` (argument
  encoding, result decoding, `ConvexError` on failure, and no action retry);
  codegen output shape is covered by `convex-codegen`'s tests.
- Integration tests (`:integration-tests`) boot the pinned backend with
  Testcontainers and deploy the conformance project into it, then exercise
  subscription, mutation, action, admin auth, storage upload/download,
  optimistic updates, and the HTTP functions API against the running server.
  No mocked server responses.
- Parity: `ParityCoverageTest` requires every upstream `convex-rs` test to be
  accounted for in `parity.yaml` (60 tests; 9 `ported`, the rest `planned`).

## Known gaps

Ordered roughly by how likely they are to matter.

1. **JWT verification on Apple targets.** `verifySignature` throws
   `NotImplementedError` on iOS; tokens are forwarded to the backend, which
   verifies them. JVM and Android verify locally.
2. **Pagination helpers.** Each query's journal is carried across reconnects,
   but there is no paged-query API on top of it; Convex pagination is assembled
   from a cursor argument the app defines.
3. **Parity depth.** Coverage enforcement is real, but most entries are
   `planned`; porting them is ongoing work.
4. **Codegen runs by hand.** `convex-codegen` ships a CLI
   (`--spec/--package/--object/--out`), but nothing fetches the spec or
   regenerates the file in `build`; a project wires its own step, and the
   generated source is committed and diffed.
5. **Internal functions are not filtered.** `ApiSpecParser` ignores the
   `visibility.kind` the backend emits, so an `internal` function would still
   be generated as a callable public descriptor.
6. **`returns` is often absent.** JS projects that do not declare return
   validators produce `returns: null`, so results are typed as `ConvexValue`
   rather than generated classes; declaring returns (as the conformance
   functions now do) yields typed results.

Closed during refinement: automatic reconnection with exponential backoff,
mutation/action call timeouts, `TransitionChunk` reassembly, server `AuthError`
surfacing, typed descriptor-based calls (codegen wiring, typed arguments and
results, `ConvexValue` fallback, no-retry actions), the codegen CLI and its
tests, pagination-journal carry, storage transfers (fixture-proven against the
pinned backend), optimistic updates (`OptimisticUpdate`, shown until the next
transition), the one-off HTTP functions API (`ConvexHttpApi`), and two more
parity ports.

## Upstream pins

- `convex-backend` `d2ca8533c52e2dbd8e55e20181988973dcbabe41`
- `convex-rs` `a4d04a9f4990078693760c2a08d0032323aa7f1b` (the submodule)
