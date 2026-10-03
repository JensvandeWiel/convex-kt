# Sync client

`ConvexSyncClient` (in `convex-client`) owns one session: the WebSocket,
the send/receive loops, the subscriptions you asked for, and the results the
server sent back. The sync state machine itself lives in `convex-core`
(`LocalSyncState`, `RemoteQuerySet`); the client is the coroutine plumbing
around it.

## Lifecycle

```kotlin
client.connect()    // opens the session; throws if already connected
client.reconnect()  // drops the transport and restores the session
client.close()      // terminal: cancels everything, fails in-flight calls
```

`close()` is terminal — a closed client cannot be reopened. Watch
`client.connectionState` (`Disconnected` → `Connecting` → `Connected`)
for UI and diagnostics.

`connect()` sends `Connect` (with a fresh `connectionCount`), replays
authentication, then re-establishes every subscription from version zero
with known journals. `reconnect()` does the same against the server's
amnesia: identity version resets, the auth token is force-refreshed, and
subscriptions are re-added. Known results stay on screen until the new
session's transitions replace them.

## Subscriptions and results

```kotlin
val subscriber = client.subscribe("messages:list", mapOf("limit" to ConvexValue.Int64(50)))
val id = subscriber.queryId

client.results.collect { results ->
    when (val result = results[id]) {
        is ConvexResult.Success -> render(result.value)
        is ConvexResult.Failure -> renderError(result.error)
        null -> renderEmpty() // no transition for this query yet
    }
}

client.unsubscribe(subscriber.subscriberId)
```

`subscribe` may be called before `connect()` — the request is queued and
sent once the session opens. Identical subscriptions share one server-side
query. Unsubscribing the last subscriber removes the server query.

## Mutations, actions, timeouts

```kotlin
val result: ConvexResult = client.mutate(
    "messages:send",
    mapOf("body" to ConvexValue.String("hello")),
    optimistic = OptimisticUpdate { results -> /* predicted map */ },
)
```

`mutate` and `action` suspend until the matching response arrives.
`callTimeoutMillis` (a client constructor argument, `null` by default)
fails the call with `ConvexClientException` instead of waiting forever;
pass an explicit timeout for any call the UI blocks on.

Two rules govern failures:

1. **Calls do not survive a connection.** When the transport drops or is
   replaced, every in-flight call fails fast. The new session never answers
   the old session's request ids, so hanging would only delay the inevitable.
   Retry the call yourself after reconnecting — never blindly for actions,
   whose side effects may already have executed.
2. **Optimistic predictions are per call.** Each `mutate`/`action` layers
   its own prediction over the server's results; answering one call never
   clears another's. A server transition clears every prediction at once.

## Reconnects

By default (`ReconnectPolicy.automatic`) an unexpected disconnect redials
with backoff (`initialDelay`, `maxDelay`, `multiplier` as `Duration`s).
Two cases force a fresh session deliberately:

- A **version gap** in transitions means frames were lost; the local view
  cannot be repaired incrementally, so the client drops the connection and
  redials rather than serve stale data silently.
- A **malformed frame** ends the session the same way.

Set `ReconnectPolicy(automatic = false)` to stay down after a drop and
drive `reconnect()` yourself.

## Auth wiring

```kotlin
val client = ConvexSyncClient(
    factory = ...,
    scope = ...,
    authFetcher = AuthTokenFetcher { forceRefresh ->
        if (forceRefresh) tokens.refresh() else tokens.current()
    },
)
```

The fetcher supplies `AuthenticationToken.Admin(value)`,
`AuthenticationToken.User(jwt)`, or `AuthenticationToken.None`. It runs on
connect and, with `forceRefresh = true`, on every reconnect. Do not call
back into the client from inside the fetcher — it runs while the client's
lifecycle lock is held.

When the server rejects or expires a token, the client keeps its
subscriptions and emits the reason on `client.authErrors` (buffered, newest
drops when a burst outruns the collector). Refresh credentials and keep
rendering; the next reconnect picks the new token up.

## Concurrency contract

Subscription state is confined to the client's `scope` dispatcher — call
`subscribe`/`unsubscribe` from there (a `LaunchedEffect`, `MainScope`, or a
single-threaded scope you own). The lifecycle methods are additionally
mutex-serialized, so a manual `reconnect()` racing the automatic redial
cannot interleave two connection establishments.

## One-off calls without a subscription

For scripts and single calls, `ConvexHttpApi` speaks the plain HTTP API
instead of holding a session open. See [Calls and codegen](calls-and-codegen.md).
