# Calls and codegen

Three ways to invoke a Convex function: untyped calls on the sync client,
typed calls through generated descriptors, and one-off HTTP calls with no
session.

## Untyped calls

```kotlin
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.value.ConvexValue

val result: ConvexResult = client.mutate("messages:send", mapOf("body" to ConvexValue.String("hi")))
val answer: ConvexResult = client.action("messages:echo", mapOf("body" to ConvexValue.String("ping")))
```

Paths are `module:function` strings, arguments a single
`Map<String, ConvexValue>`. Results are `ConvexResult.Success(value)` or
`ConvexResult.Failure(error)` where `error` is `ErrorPayload.Message` or
`ErrorPayload.ErrorData` (structured `ConvexError` payloads — the codec
branches on the *presence* of `errorData`, never on a nullable value).

Actions return `Result?` in typed form: a JSON `null` decodes to Kotlin
`null` rather than failing.

## Typed calls with generated descriptors

`convex-codegen` turns a Convex API spec into descriptors, so argument
shapes and result types are compile errors instead of runtime surprises:

```bash
convex-codegen --spec api-spec.json --package com.example.convex \
    --object Api --out src/commonMain/kotlin/com/example/convex/Api.kt
```

The spec format tolerates the shapes real tooling emits (`name` or
`module`+`functionName`, `functionType` or `type`); unknown validators
degrade to `ConvexValidator.Any` rather than failing the generation. Then:

```kotlin
client.subscribe(Api.Messages.list)
client.mutate(Api.Messages.send, SendMessageRequest("hi"))
val echo: EchoResponse? = client.action(Api.Messages.echo, EchoRequest("hi"))
```

When the generator cannot model a function's `returns`, the descriptor
carries no result serializer and `Result` must be `ConvexValue` — the raw
value is returned unchanged. That pairing is a documented contract, not a
runtime check (the type is erased), so always use generated descriptors
rather than hand-writing them.

## Optimistic updates

```kotlin
client.mutate("messages:send", mapOf("body" to ConvexValue.String(body))) { results ->
    results + (queryId to ConvexResult.Success(ConvexValue.String(body)))
}
```

The lambda is an `OptimisticUpdate`: a pure function from current results to
predicted results, shown until the server transition supersedes it. Keep
implementations pure and total — they run on every publish.

## HTTP API without a session

```kotlin
import eu.wynq.convex.client.ConvexHttpApi
import io.ktor.client.HttpClient

val http = ConvexHttpApi("https://example.convex.cloud", HttpClient())
val result: ConvexResult = http.query("messages:list", emptyMap(), authHeader = "Bearer $jwt")
```

`query`, `mutation`, and `action` POST to `/api/query|mutation|action`
with `{"path", "args", "format": "json"}`. The optional `authHeader` is
sent as `Authorization` verbatim (`Bearer <jwt>`, `Convex <admin-key>`).
Non-2xx responses throw `ConvexClientException`.
