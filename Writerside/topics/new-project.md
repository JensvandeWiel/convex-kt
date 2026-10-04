# Build a new project

This is the full path from an empty directory to a Compose Multiplatform app
that talks to your own Convex backend through generated, typed call sites:
backend, build-time code generation, and a live subscription. If you already
have a backend and only want to add the client, use
[Getting started](getting-started.md) instead.

## Requirements

- JDK 21 or newer.
- Node.js 20+ and npm.
- The Android SDK 36 (with `local.properties` or `ANDROID_HOME`) to build the
  Android target, and Xcode to build iOS. Desktop needs neither.

## 1. Create the backend

```bash
mkdir my-convex-app
cd my-convex-app
npm init -y
npm install convex
```

The first `npx convex dev` signs you in, creates a dev deployment, and writes
the generated backend module into `convex/_generated/`:

```bash
npx convex dev
```

When it reports the functions are pushed, stop it with `Ctrl-C`.

## 2. Write a module

Create `convex/messages.ts` with a query, a mutation, and an action:

```ts
import { action, mutation, query } from "./_generated/server";
import { v } from "convex/values";

export const list = query({
  args: {},
  returns: v.array(
    v.object({
      _id: v.id("messages"),
      _creationTime: v.number(),
      body: v.string(),
    }),
  ),
  handler: async (ctx) => {
    return await ctx.db.query("messages").collect();
  },
});

export const send = mutation({
  args: { body: v.string() },
  returns: v.number(),
  handler: async (ctx, { body }) => {
    await ctx.db.insert("messages", { body });
    return body.length;
  },
});

export const echo = action({
  args: { body: v.string() },
  returns: v.string(),
  handler: async (_ctx, { body }) => body,
});
```

Declare `args` and `returns` with `v` validators: those validators are exactly
what the Kotlin generator turns into types. A missing `returns` still works, but
the generated result type degrades to `ConvexValue`.

Push the module once, then leave the deployment alone:

```bash
npx convex dev --once
```

## 3. Create the app

Use the JetBrains Kotlin Multiplatform wizard, or scaffold the app by hand: a
`composeApp` module with `commonMain`, `androidMain`, `desktopMain`, and
`iosMain` source sets, and the version catalog the wizard generates. The client
libraries are target-agnostic, so any target works. The rest of this guide
assumes the module is named `composeApp`.

## 4. Add the dependencies and the plugin

Add the client libraries and the codegen plugin to `gradle/libs.versions.toml`:

```toml
[versions]
convex = "0.2.0"
convexCodegen = "0.2.0"

[libraries]
convex-client = { module = "eu.wynq.convex:convex-client", version.ref = "convex" }
convex-compose = { module = "eu.wynq.convex:convex-compose", version.ref = "convex" }

[plugins]
convexCodegen = { id = "eu.wynq.convex.codegen", version.ref = "convexCodegen" }
```

Then wire them into `composeApp/build.gradle.kts`. Keep the Kotlin Multiplatform,
Android application, and Compose plugins the wizard already added, and add the
serialization plugin (the generated file uses `@Serializable`) and the codegen
plugin. The wizard's catalog names the serialization plugin
`kotlinSerialization`; if yours differs, use its id
(`org.jetbrains.kotlin.plugin.serialization`):

```kotlin
plugins {
    // ...the wizard's Kotlin, Android, and Compose plugins...
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.convexCodegen)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.convex.client)
            implementation(libs.convex.compose) // Compose bindings
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
}

convexCodegen {
    packageName = "com.example.myapp.convex"
    // Recommended for a new project: let the build push the backend before it
    // captures the apiSpec, so the generated Kotlin can never lag the backend.
    prepareCommand = listOf("npx", "--yes", "convex", "dev", "--once")
}
```

`packageName` is the only required setting. The plugin captures the backend
`apiSpec` itself on every build by running `npx --yes convex function-spec` in
the project root, so there is no manual JSON step. `prepareCommand` optionally
runs first — `npx convex dev --once` pushes the backend before capture, so one
Gradle build deploys and regenerates.

The other defaults are `objectName = Api`, `sourceSet = commonMain`,
`convexProjectDirectory` = the root project directory, and
`functionSpecCommand` = `["npx", "--yes", "convex", "function-spec"]`.

The plugin is resolved like any other Gradle plugin, so `mavenCentral()` must be
in `pluginManagement.repositories` in `settings.gradle.kts` (the KMP wizard puts
it there).

## 5. Generate the typed API

```bash
./gradlew :composeApp:generateConvexApi
```

The plugin captures the spec, parses it, writes
`composeApp/build/generated/convex/Api.kt`, and adds that directory to
`commonMain`, so the next compile sees it. You do not have to run the task by
hand: a normal build runs it automatically.

```bash
./gradlew :composeApp:build
```

The capture always runs — the spec is a view of a remote deployment, not of
local files, so a cached result could be stale. Generation runs only when the
captured spec changed.

Generated sources live under `build/` and are derived from the spec — do not
commit them.

## 6. Call the backend from Compose

The generated object mirrors the module layout: `Api.Messages.list`,
`Api.Messages.send`, and `Api.Messages.echo`. Argument shapes and result types
come from the validators, so a wrong call is a compile error:

```kotlin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.example.myapp.convex.Api
import eu.wynq.convex.client.ConvexSyncClient
import eu.wynq.convex.client.KtorSyncProtocolFactory
import eu.wynq.convex.client.syncUrl
import eu.wynq.convex.compose.QueryState
import eu.wynq.convex.compose.rememberQuery
import kotlinx.coroutines.launch

@Composable
fun Messages() {
    val scope = rememberCoroutineScope()
    val client = remember {
        ConvexSyncClient(
            factory = KtorSyncProtocolFactory(syncUrl("https://your-deployment.convex.cloud")),
            scope = scope,
        )
    }
    LaunchedEffect(client) { client.connect() }
    DisposableEffect(client) { onDispose { scope.launch { client.close() } } }

    when (val state = rememberQuery(client, Api.Messages.list)) {
        QueryState.Loading -> CircularProgressIndicator()
        is QueryState.Failure -> Text("Error: ${state.error.message}")
        is QueryState.Success -> LazyColumn {
            items(state.value) { message -> Text(message.body) }
        }
    }

    Button(onClick = { scope.launch { client.mutate(Api.Messages.send, Api.Messages.SendRequest("hello")) } }) {
        Text("Send")
    }
}
```

Use the URL of your deployment; a self-hosted backend is
`http://127.0.0.1:3210`.

## 7. Run it

- Desktop: `./gradlew :composeApp:run`
- Android: launch the app configuration from your IDE, or
  `./gradlew :composeApp:installDebug`
- iOS: open the generated `iosApp` in Xcode and run it

## Keeping the generated API in sync

If you set `prepareCommand` (or wired the backend into Gradle, below), nothing
else is needed: a normal build pushes the backend, captures the spec, and
regenerates.

Otherwise run your Convex watcher while you develop, and let the build capture
the current deployment:

```bash
npx convex dev        # in a second terminal, watches and pushes functions
./gradlew :composeApp:build
```

Either way, a function you added shows up as a new property and a function you
removed stops compiling.

## Using a committed spec instead

For an offline build, an air-gapped CI, or a spec you want to review in a pull
request, write the JSON yourself and point the plugin at it:

```bash
npx convex function-spec > convex/api-spec.json
```

```kotlin
convexCodegen {
    packageName = "com.example.myapp.convex"
    spec = layout.projectDirectory.file("convex/api-spec.json")
}
```

When `spec` is set, the plugin reads that file and never runs the Convex CLI or a
`prepareCommand`, so the build is hermetic. Commit the file and regenerate it
whenever the backend changes.

## 8. Include the Convex backend in the Gradle build (optional)

The plain setup runs `npx` commands through `prepareCommand` and
`functionSpecCommand`, which is all you need. If you would rather have one
`./gradlew build` own the backend — and not depend on a global `npx` — the Convex
project can be a Gradle module, too, using the
[Gradle Node plugin](https://github.com/node-gradle/gradle-node-plugin):

```toml
[plugins]
node = { id = "com.github.node-gradle.node", version = "7.1.0" }
```

Add the module in `settings.gradle.kts`:

```kotlin
include(":convex-backend")
```

Create `convex-backend/package.json`, pinned like any other dependency, next to
the `convex/` folder with your functions:

```json
{
  "name": "my-convex-app-backend",
  "private": true,
  "dependencies": { "convex": "1.46.0" }
}
```

Then give it a build file. `composeApp` lives in the same repository, so it can
run `npx` out of this module's own `node_modules`:

```kotlin
import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.npm.task.NpxTask

plugins {
    `java-library` // a standard `build` lifecycle task for this module
    alias(libs.plugins.node)
}

node {
    download.set(true) // fetch Node instead of relying on the PATH
    version.set("22.11.0")
    workDir.set(layout.projectDirectory.dir(".gradle-node"))
}

// Keyed to package.json rather than to node_modules, which the plugin treats as
// always stale; the Node download itself is a separate, cacheable task.
val npmInstall by tasks.registering(NpmTask::class.java) {
    group = "convex"
    description = "Installs the Convex CLI for the backend module."
    args.set(listOf("install"))
    outputs.upToDateWhen {
        layout.projectDirectory.file("node_modules/convex/package.json").asFile.isFile
    }
}

// Push the backend. Always runs: a deployment is remote state, not a local file.
val convexDeploy by tasks.registering(NpxTask::class.java) {
    group = "convex"
    description = "Pushes the Convex functions to the configured deployment."
    dependsOn(npmInstall)
    command.set("convex")
    args.set(listOf("deploy", "-y"))
    outputs.upToDateWhen { false }
}

tasks.named("build") { dependsOn(convexDeploy) }
```

Point the codegen plugin at this module's `npx`, and let the build push before it
captures. Passing the task provider wires the dependency for you:

```kotlin
// composeApp/build.gradle.kts
import com.github.gradle.node.npm.task.NpxTask

convexCodegen {
    packageName = "com.example.myapp.convex"
    convexProjectDirectory = rootProject.layout.projectDirectory.dir("convex-backend")
    functionSpecCommand = listOf("npx", "--yes", "convex", "function-spec")
    prepareCommand = rootProject.tasks.named("convexDeploy", NpxTask::class.java)
        .map { listOf("npx", "--yes", "convex", "deploy", "-y") }
}
```

With that in place, `./gradlew build` installs the Convex CLI, pushes the
functions, captures the apiSpec, and regenerates `Api.kt` before `composeApp`
compiles. `convex/` stays where the Convex CLI expects it, and `convexDeploy`
needs the deployment configured first (`npx convex dev` once interactively, or
`CONVEX_DEPLOY_KEY` in CI). Whether this coupling is worth it depends on your
workflow; `prepareCommand` alone covers most projects.

## Where to next

- [Sync client](sync-client.md) for reconnects, timeouts, and errors.
- [Calls and codegen](calls-and-codegen.md) for actions, HTTP calls, and
  optimistic updates.
- [Compose](compose.md) for the query bindings in more detail.
