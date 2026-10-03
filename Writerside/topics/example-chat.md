# Example: chat

`examples/chat` is a minimal Compose Desktop app exercising the whole
stack end to end: live subscription, mutation, optimistic update, and
connection-state display. Run it when you want to feel a change rather
than assert it.

## Run it

You need the pinned backend (Docker) with functions deployed, then the app
pointed at it:

```bash
# 1. Start the backend (pinned image; ports 3210-3211):
docker run --name convex-kt-backend -p 3210-3211:3210-3211 \
    ghcr.io/get-convex/convex-backend:d2ca8533c52e2dbd8e55e20181988973dcbabe41

# 2. Deploy the functions (generates an admin key, then pushes):
docker exec convex-kt-backend ./generate_admin_key.sh  # -> convex-self-hosted|…
cd conformance/harness/project
CONVEX_SELF_HOSTED_URL=http://127.0.0.1:3210 \
CONVEX_SELF_HOSTED_ADMIN_KEY='convex-self-hosted|…' \
    npx convex deploy -y   # needs `npm install` here once

# 3. Run the app (JDK 21):
CONVEX_URL=http://127.0.0.1:3210 ./gradlew :examples:chat:run
```

`CONVEX_URL` defaults to `http://127.0.0.1:3210`, so step 3 alone suffices
against a local backend. To stop the backend: `docker stop` / `docker rm
convex-kt-backend` (data is ephemeral unless you mount a volume).

## What it demonstrates

`Main.kt` (~170 lines, heavily commented as a worked example):

- A `ConvexSyncClient` over `KtorSyncProtocolFactory(syncUrl(url))`,
  connected in a `LaunchedEffect` with failures shown as connection errors.
- A `QueryController(client, "messages:list", decoder = …)` whose `state`
  drives a `LazyColumn`; disposing the composition unsubscribes.
- Sending via `client.mutate("messages:send", …)` with a `predictAppend`
  optimistic update, so the message appears before the server confirms it.
- `client.connectionState` rendered in the window chrome.

It deliberately uses untyped string paths and a hand-written decoder
rather than generated descriptors, so it stays readable as a first read;
for the typed path see [Calls and codegen](calls-and-codegen.md).

The app is held to the same formatting and static-analysis gates as the
libraries (`convex-quality`), but not the coverage floor — there is no
unit-testable logic in a Compose UI over gated libraries.
