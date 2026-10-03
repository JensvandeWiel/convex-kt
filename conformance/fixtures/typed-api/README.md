# typed-api fixture

The real `apiSpec` from the pinned backend, used to generate the typed-call
descriptors that `convex-client`'s tests compile and exercise.

## What it is

The bare function array returned by the `_system/cli/modules:apiSpec` system
query against the deployment of `conformance/harness/project`. Every function
declares a `returns` validator (except `messages:clear`, which returns nothing),
so codegen emits typed result classes rather than the `ConvexValue` fallback.

## How it was captured

1. Start the pinned backend:
   `docker compose -f conformance/docker-compose.yml up -d`.
2. Deploy the harness project (the same step `record-handshake.mjs` performs):
   from `conformance/harness/project`,
   `CONVEX_SELF_HOSTED_URL=http://127.0.0.1:3210 CONVEX_SELF_HOSTED_ADMIN_KEY=<key> npx convex deploy -y`.
3. `POST /api/query` with
   `{"path":"_system/cli/modules:apiSpec","args":{},"format":"json"}` and an
   admin key, then write the response's `value` array **verbatim**.
4. Regenerate the committed Kotlin source:

   ```
   ./gradlew :convex-codegen:run --args="--spec <repo>/conformance/fixtures/typed-api/api-spec.json --package eu.wynq.convex.client.generated --object GeneratedApi --out <repo>/convex-client/src/commonTest/kotlin/eu/wynq/convex/client/generated/GeneratedApi.kt"
   ```

Never hand-edit `api-spec.json`; re-capture it. The generated file is committed
so `:convex-client:jvmTest` compiles it, and generation is deterministic.

## Pins

- Backend: `d2ca8533c52e2dbd8e55e20181988973dcbabe41`.
- Harness project: `conformance/harness/project` at the commit that added the
  `returns` validators.
