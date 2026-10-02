# Sync state machine — design and decisions

**Status:** first slice implemented (local + remote query-set reduction). This
document records the reasoning and the open decisions so the direction can be
steered before the client/transport layer is built on top.

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

## Open questions (please steer)

1. **Where does the one-object argument unwrapping live?** Options: (a) the
   public API takes `Map<String, ConvexValue>` and the codec wraps; (b) the
   public API takes `ConvexValue.Object` and callers build it; (c) codegen
   produces typed arg objects. Currently (a)/(b) mix: `Query.args` is a
   `List<ConvexValue>` (raw wire form) while `subscribe` takes a map. Should the
   public surface be uniformly typed, with raw `List<ConvexValue>` internal?
2. **Optimistic update model.** `OptimisticQueryResults` upstream is a stub
   (a TODO drops optimistic updates). Should we implement real optimistic
   updates now (mutations predict query changes, dropped when the server
   transition acknowledges), or mirror the stub and defer? The plan's step 5
   says "settle the optimistic update mechanics (`QueryState<T>`)".
3. **Consistent views / `FunctionResult`.** Upstream exposes
   `FunctionResult { Value | ErrorMessage | ConvexError }`. Our `CallResult`
   (`Success | Failure(ErrorPayload)`) is close but named for calls. Should
   results and call outcomes share one type, or stay separate?
4. **Reconnect policy.** `resendQueries` resets the query-set version. Do we
   also need to drop all query results on reconnect, or keep serving stale
   values until the first new transition? Upstream keeps them and lets the
   `QueryUpdated`/`QueryRemoved` replace them.
5. **Threading / exposure.** The eventual `convex-client` needs to expose
   `Flow<QueryState<T>>`. Should the state machine own a `StateFlow` of results,
   or stay a pure reducer and let the transport publish? Pure keeps testing
   simple; a `StateFlow` is ergonomic but couples the core to coroutines.
6. **Mutation id ownership.** `RequestId` allocation lives where? Upstream
   `BaseConvexClient.mutation` allocates and tracks pending requests. Not
   implemented yet.
7. **Journal / pagination.** `Query.journal` is carried but unused. Pagination
   is a later step; confirm it can be layered without changing the state machine
   shape.

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
