# Compose

`convex-compose` binds live queries to composition: a query becomes a
`QueryState<T>` you switch over exhaustively, and `rememberQuery` owns the
subscription lifecycle for you.

## QueryState

```kotlin
import eu.wynq.convex.compose.QueryState

when (val state = rememberQuery(client, "messages:list", decoder = MessageListDecoder)) {
    QueryState.Loading -> CircularProgressIndicator()
    is QueryState.Success -> MessageList(state.value)
    is QueryState.Failure -> ErrorText(state.error.message)
}
```

- `Loading`: subscribed, no transition yet.
- `Success(value)`: the decoded model value.
- `Failure(error)`: the query failed with an `ErrorPayload` (message or
  structured error data) — render it, don't crash on it.

There is no `else`: the hierarchy is closed, so a new state breaks your
`when` at compile time instead of slipping past at runtime.

## Decoders

```kotlin
import eu.wynq.convex.compose.ConvexDecoder
import eu.wynq.convex.core.value.ConvexValue

val MessageListDecoder = ConvexDecoder<List<String>> { value ->
    (value as? ConvexValue.Array)?.value?.mapNotNull { element ->
        ((element as? ConvexValue.Object)?.value?.get("body") as? ConvexValue.String)?.value
    } ?: emptyList()
}
```

`ConvexDecoder<T>` is a `fun interface` from raw `ConvexValue` to your
model; `ConvexDecoder.identity()` passes values through when `T` is
`ConvexValue`. Decoders should be total — anything unparseable must yield a
fallback, because a throwing decoder fails the composition that collects
it. `queryStateOf(result, decoder)` performs the same mapping outside
composition (tests, previews, non-Compose UI).

## rememberQuery overloads

```kotlin
// Raw path plus decoder:
rememberQuery(client, udfPath, args, decoder)
// Descriptor plus decoder (path comes from the descriptor):
rememberQuery(client, function, args, decoder)
// Fully typed (descriptor carries both serializers):
rememberQuery(client, query, args)
rememberQuery(client, query) // Args = Unit
```

The controller is remembered on `(client, udfPath, args, decoder)` —
changing any of them resubscribes — and disposed (unsubscribed) with the
composition.

## QueryController

For non-composable owners (ViewModels, presenters), use `QueryController`
directly:

```kotlin
import eu.wynq.convex.compose.QueryController

val controller = QueryController(client, "messages:list", decoder = MessageListDecoder)
controller.state.collect { state -> /* ... */ }
controller.close() // unsubscribes
```

Constructing subscribes; several controllers on the same query share one
server subscription; `queryId` exposes which server query is observed.
