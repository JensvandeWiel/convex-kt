# convex-kt

> [!WARNING]
> This is primarily a vibe-coded project. Treat the test suite, the conformance
> fixtures, and the guidelines in `AGENTS.md` as the contract — they are
> deliberately thorough and are what keep the code honest.

An idiomatic Kotlin Multiplatform client for [Convex](https://convex.dev),
covering **Android**, **JVM/desktop**, and **iOS**. It speaks Convex's sync
WebSocket protocol directly — no Rust FFI — and offers Compose bindings on top of
coroutines and `Flow`.

## Features

- **Live queries** delivered as `Flow`/Compose state, with optimistic updates.
- **Mutations and actions** with typed, generated call sites.
- **JWT auth**, including token refresh and re-authentication on reconnect.
- **File storage** upload and download.
- **Code generation** from the backend's `apiSpec` into discoverable descriptors.

## Requirements

- Kotlin Multiplatform with coroutines; the Android/JVM targets need JDK 21.
- Apple targets (`iosX64`, `iosArm64`, `iosSimulatorArm64`) build on macOS.

## Modules

| Module | What it gives you |
| --- | --- |
| `convex-client` | The client: connect, subscribe, mutate, action |
| `convex-core` | Values, protocol messages, and the sync state machine (pulled in transitively) |
| `convex-compose` | `rememberQuery` and `QueryState<T>` for Compose |
| `convex-auth` | JWT parsing and RS256/ES256 verification |
| `convex-storage` | Upload and download files |
| `convex-codegen` | Build-time generator for typed call sites |

Dependencies point inward: `convex-client`, `convex-auth`, and `convex-storage`
depend on `convex-core`; `convex-compose` depends on `convex-client` and
`convex-core`. A client-only app needs just `convex-client`.

## Add the dependency

The group is `eu.wynq.convex` and the current version is `0.1.0`. No Maven
artifacts are published yet, so consume the modules from this repository (for
example as a Gradle composite build):

```kotlin
implementation("eu.wynq.convex:convex-client:0.1.0")
```

## Quick start

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val client = ConvexSyncClient(
    factory = KtorSyncProtocolFactory(syncUrl("https://your-deployment.convex.cloud")),
    scope = scope,
)

// `subscribe` may be called before connecting; the client establishes the
// query set once the connection opens and restores it on reconnect.
client.connect()
val subscriber = client.subscribe("messages:list")

// Results are exposed as a StateFlow keyed by query id.
client.results.collect { results -> /* render results[subscriber.queryId] */ }

// A mutation can be optimistic: the prediction is shown until the next
// transition from the server replaces it.
val length: ConvexResult = client.mutate(
    "messages:send",
    mapOf("body" to ConvexValue.String("hi")),
)
```

## Typed calls

`convex-codegen` turns the backend's `apiSpec` into descriptors, so a call site
carries the function's kind, argument type, and result type:

```kotlin
import com.example.api.Api

client.subscribe(Api.Messages.list)                                  // no-arg: argument omitted
client.subscribe(Api.Messages.search, SearchInput(limit = 20))       // query input
val length: Long = client.mutate(Api.Messages.send, SendMessageRequest("hi"))
val echo: String? = client.action(Api.Messages.echo, EchoRequest("hi")) // actions may return nothing
```

- Queries take an `<Function>Input`; mutations and actions take a
  `<Function>Request`. A no-argument function emits no argument type, and the
  no-argument overload is the only one that accepts it.
- Mutations return a non-null result; actions return a nullable one, because an
  action may return nothing.
- An action is **never retried** on reconnect: it may have side effects.
- Results are generated from the function's `returns` validator. When the
  backend does not declare `returns`, the result is a `ConvexValue` — the value
  is still delivered, only its static type is generic.

Generate the descriptors with the CLI:

```bash
./gradlew :convex-codegen:run --args="--spec api-spec.json --package com.example.api --object Api --out Api.kt"
```

`CONTRIBUTING.md` documents how to fetch `api-spec.json` from a running backend.

## Compose

```kotlin
@Composable
fun Messages(client: ConvexSyncClient) {
    when (val state = rememberQuery(client, Api.Messages.list)) {
        QueryState.Loading -> CircularProgressIndicator()
        is QueryState.Failure -> Text("Error: ${state.error.message}")
        is QueryState.Success -> LazyColumn {
            items(state.value) { message -> Text(message.body) }
        }
    }
}
```

`rememberQuery` subscribes for the life of the composition and disposes the
subscription when it leaves.

## Authentication

Pass an `AuthTokenFetcher` to the client; it is called on connect and again on
reconnect with `forceRefresh = true`, so an app can refresh an expired token:

```kotlin
val client = ConvexSyncClient(
    factory = factory,
    scope = scope,
    authFetcher = AuthTokenFetcher { forceRefresh ->
        AuthenticationToken.User(loadToken(forceRefresh))
    },
)
```

`convex-auth` verifies RS256/ES256 signatures locally on all targets: JVM and
Android through `java.security`, and Apple targets through Security.framework.
The same fixed RS256/ES256 vectors are asserted on every target, so the three
backends are held to one behavior. A token is still forwarded to the backend,
which remains the final authority; local verification only lets an app reject a
bad token early.

## Storage

```kotlin
val storage = ConvexStorageClient(httpClient)

// 1. Get a pre-signed URL from an app mutation (`storage.generateUploadUrl`).
// 2. Upload, and receive the new storage id.
val storageId = storage.upload(uploadUrl, bytes, contentType = "image/png")

// 3. Exchange the id for a signed URL through an app query (`storage.getUrl`).
// 4. Download.
val bytes = storage.download(fileUrl)
```

## Platform notes

- **JWT verification** is local on every target: `java.security` on JVM/Android
  and Security.framework on Apple. The backend still verifies independently.
- **Pagination** is not wrapped by a helper; each query's `journal` is carried
  across reconnects, and an app assembles Convex pagination from a cursor
  argument.
- **Code generation** is a CLI step; the generated file is meant to be committed
  and diffed.
- **Value export.** `ConvexValue.export()` projects a value into the plain-JSON
  "database types" format (integers and bytes become strings, non-finite floats
  become sentinels). This is lossy and separate from the tagged wire codec in
  `ConvexJson`; round-tripping an exported value needs the original validator.

## Example

`examples/chat` is a Compose Desktop app that subscribes to `messages:list` and
sends `messages:send`:

```bash
CONVEX_URL=http://127.0.0.1:3210 ./gradlew :examples:chat:run
```

## Documentation

Two complementary surfaces, deliberately split:

- **User guides** — task-oriented docs for every feature live in the
  `Writerside/` help module (open it with the JetBrains Writerside plugin;
  its landing page is the entry point). Build the HTML with the tag pinned in
  `wrs-supernova`:

  ```bash
  WRS=$(grep -v '^#' wrs-supernova | head -1)
  tmp=$(mktemp -d) && cp -R Writerside "$tmp/"
  docker run --rm --platform linux/amd64 \
    -e ALT_CEF_SERVER_PATH=/opt/builder/jbr/lib/cef_server \
    -e JCEF_DISABLE_GPU=true \
    -e JAVA_TOOL_OPTIONS="-DALT_CEF_SERVER_PATH=/opt/builder/jbr/lib/cef_server -DJCEF_DISABLE_GPU=true" \
    -v "$tmp:/opt/sources" "$WRS" \
    /bin/bash -c "export DISPLAY=:99 && Xvfb :99 & /opt/builder/bin/idea.sh helpbuilderinspect --source-dir /opt/sources --product Writerside/convex-kt --runner other --output-dir /opt/sources/output"
  # artifacts: $tmp/output/webHelpCONVEX-KT2-all.zip
  ```

  The `ALT_CEF_SERVER_PATH`/`JCEF_DISABLE_GPU` overrides are needed when the
  amd64 image runs under Docker's emulation (Apple Silicon), where the JVM
  cannot resolve its own process command; they are harmless on amd64 hosts.
- **API reference** — KDoc on every public declaration, generated with
  Dokka into one combined site: `./gradlew dokkaGenerateHtml`, output in
  `build/dokka/html`. Dokka carries API definitions only, never prose
  guides.

A GitHub release publishes both surfaces to GitHub Pages
(`.github/workflows/docs.yml`): the guide at
<https://jensvandewiel.github.io/convex-kt/> and the API reference under
`/api/`.

## Contributing

The contributor workflow — architecture, coding conventions, the quality gates,
and how to record conformance fixtures — lives in `CONTRIBUTING.md`.
`AGENTS.md` is the authoritative working agreement.

## License

Apache License 2.0. See `LICENSE`. All source files carry the standard header,
enforced by Spotless.
