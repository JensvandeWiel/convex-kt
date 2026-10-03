# Getting started

Add the libraries, open a client, subscribe to a query, run a mutation.
Ten minutes, end to end.

## Requirements

- JDK 21 or newer.
- For Android: SDK 36 with `local.properties` (`sdk.dir=...`) or
  `ANDROID_HOME`.
- For the sample backend in this guide: Docker (the pinned backend image)
  and Node 20+.

## Add the dependencies

`convex-kt` modules are plain Gradle dependencies. Pick what you use;
`convex-client` pulls in `convex-core` automatically.

```kotlin
// build.gradle.kts
dependencies {
    implementation("eu.wynq.convex:convex-client:0.1.0-SNAPSHOT")
    implementation("eu.wynq.convex:convex-compose:0.1.0-SNAPSHOT") // Compose UI only
    implementation("eu.wynq.convex:convex-auth:0.1.0-SNAPSHOT") // JWT verification only
    implementation("eu.wynq.convex:convex-storage:0.1.0-SNAPSHOT") // file transfer only
}
```

The JVM/Android artifacts come from the `jvm` and `androidTarget`
compilations; Apple targets (`iosX64`, `iosArm64`, `iosSimulatorArm64`)
are in the same published modules.

## Open a client and subscribe

```kotlin
import eu.wynq.convex.client.ConvexSyncClient
import eu.wynq.convex.client.KtorSyncProtocolFactory
import eu.wynq.convex.client.syncUrl
import eu.wynq.convex.core.value.ConvexValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

val client = ConvexSyncClient(
    factory = KtorSyncProtocolFactory(syncUrl("https://example.convex.cloud")),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
)

client.connect()
val subscriber = client.subscribe("messages:list")

// Live results, updated on every server transition:
client.results.collect { results ->
    println(results[subscriber.queryId])
}
```

`syncUrl` accepts a deployment origin (`https://…`) or an already-`ws(s)://`
URL and always targets `/api/sync`. Against a local backend it is
`syncUrl("http://127.0.0.1:3210")`.

## Run a mutation

```kotlin
client.mutate("messages:send", mapOf("body" to ConvexValue.String("hello")))
```

`mutate` suspends until the server answers. Results arrive twice: once as
the mutation's return value, and once through the subscription when the
transition lands. See [Sync client](sync-client.md) for timeouts, errors,
and what happens on reconnect.

## Next steps

- [Sync client](sync-client.md) for the connection lifecycle.
- [Calls and codegen](calls-and-codegen.md) for typed calls and actions.
- [Example: chat](example-chat.md) for a runnable app doing all of this.
