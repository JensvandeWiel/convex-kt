# Sync state machine — design and decisions

**Status:** local and remote query-set reduction implemented; the client wires
them together over the transport seam. Optimistic updates and automatic backoff
reconnection remain (see `docs/STATUS.md`). This document records the reasoning
and the decisions so the direction can be steered.

## Goal

`convex-core` owns a pure, transport-agnostic state machine that turns a desired
set of subscription intents plus incoming `ServerMessage`s into:

- outgoing `ClientMessage`s (subscriptions, auth, mutations/actions), and
- a consistent view of query results.

`convex-client` (step 5) will own the WebSocket, the coroutine plumbing, and the
optimistic-update policy. Keeping the state machine pure is what makes it
testable against the recorded fixtures without a network.

## Upstream mapping

`convex-rs` `src/base_client/mod.rs` splits the work into three pieces, and this
port keeps the same seams:

| Upstream | Kotlin (this slice) | Responsibility |
| --- | --- | --- |
| `LocalSyncState` | `LocalSyncState` | subscription intent → `ClientMessage`, query-id allocation |
| `RemoteQuerySet` | `RemoteQuerySet` | apply `Transition`s, track version + results |
| `OptimisticQueryResults` | **not yet** | merge server results with optimistic updates |
| `BaseConvexClient` | **not yet** | orchestration, outgoing queue, `FunctionResult` view |

`QueryToken` (canonical `udfPath` + args) deduplicates identical subscriptions:
subscribing twice to `messages:list {}` reuses one query id.

## Decisions

1. **Pure functions, caller assigns.** A `&mut self` method becomes a function
   returning the new state and any message, rather than hidden mutation where
   convenient. Where state is genuinely internal (`LocalSyncState`), it mutates
   its own fields but always returns the consequences explicitly
   (`Subscription(message, subscriberId)`).
2. **`SubscriberId` carries `(queryId, index)`.** A second subscriber to the
   same query gets a new index; the first unsubscribe of two emits no message.
   This mirrors upstream and is what makes "last unsubscribe removes the query"
   correct.
3. **Version arithmetic is explicit.** `subscribe`/`unsubscribe` bump
   `querySetVersion`; `authenticate` bumps `identityVersion`; `resendQueries`
   resets the query-set version to zero and re-adds everything (reconnect path).
4. **`RemoteQuerySet` rejects a `Transition` whose `startVersion` is not the
   current version.** Upstream returns a `ReconnectProtocolReason`; we return
   `TransitionOutcome.VersionMismatch`, so the caller decides to reconnect. It
   does not throw, because a mismatch is an expected protocol event, not a bug.
5. **`QueryFailed` with `errorData == null` is an ordinary error; a present
   payload (including a present `null`) is a `ConvexError`.** This is the
   key-presence rule carried through to the domain type.
6. **Args are a single object.** Convex query/mutation arguments are one object;
   the wire wraps it as `[args]`. The Kotlin API takes `Map<String, ConvexValue>`
   and the codec does the wrapping.
7. **No coroutines in `convex-core`.** The state machine is synchronous. Flows,
   channels, and cancellation are `convex-client` concerns (step 5). This keeps
   the core testable with plain unit tests and avoids the `.await`-in-a-loop
   deadlock trap entirely.

## Decisions resolved in review

1. **Argument typing.** The public surface takes `Map<String, ConvexValue>` (and
   later codegen'd typed args); `Query.args` stays the raw wire form and is
   internal to the codec. The ergonomic API and the faithful wire model are kept
   separate.
2. **Optimistic updates.** Deferred to their own slice, mirroring the upstream
   stub for now. `RemoteQuerySet` remains the server truth; an
   `OptimisticQueryResults` layer will be added later.
3. **Result type.** Query results and call outcomes share one `ConvexResult`
   (`Success | Failure(ErrorPayload)`), matching upstream `FunctionResult`.
   `CallResult` was renamed accordingly.
4. **Reconnect.** Keep known values until the server's transitions replace them;
   `resendQueries` only resets the version.
5. **Reactive exposure.** `convex-core` stays a pure synchronous reducer.
   `Flow`/`StateFlow` and `QueryState<T>` live in `convex-client` and
   `convex-compose`, never in the core.

## Remaining open items

- **Mutation tracking.** Where `RequestId` allocation and pending-request
  bookkeeping live (likely `convex-client`, or a small core slice).
- **Pagination / journal.** `Query.journal` is carried but unused; confirm it can
  be layered on without changing the state-machine shape.
- **Auth refresh.** Implemented: `AuthTokenFetcher` is called on connect and
  again from `reconnect` with `forceRefresh = true`, and the identity version is
  reset before re-authenticating.
- **Automatic reconnection.** Implemented: a lost connection triggers a
  backoff loop via `ReconnectPolicy`; a manual `reconnect()` remains available.

## Test strategy

- `LocalSyncStateTest`: id reuse, subscriber indexing, last-unsubscribe removal,
  version bumps, authenticate versioning, resend.
- `RemoteQuerySetTest`: value/error/remove application, version tracking,
  version-mismatch detection, key-presence mapping for `errorData`.
- Later: drive these against `conformance/fixtures/query-and-mutation` so a
  transition sequence is replayed end to end (the fixture test already decodes
  the frames; replaying them through the state machine is the natural next
  step).

## Explicitly out of scope here

WebSocket transport, reconnect loops, auth token refresh, optimistic updates,
pagination, and the `QueryState<T>` Compose binding.
