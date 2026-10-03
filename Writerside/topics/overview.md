# convex-kt user handbook

Task-oriented guides for every feature. The API reference (KDoc for every
public declaration) is generated with Dokka (`./gradlew dokkaGenerateHtml`);
this handbook is the narrative companion — start here, then drop into the
reference for signatures.

## Contents

1. [Getting started](getting-started.md) — dependencies, first connection,
   first subscription, first mutation.
2. [Sync client](sync-client.md) — connections, subscriptions, results,
   timeouts, reconnects, auth wiring, and what happens when things break.
3. [Calls and codegen](calls-and-codegen.md) — untyped and typed queries,
   mutations, actions, optimistic updates, the HTTP API, and generating
   descriptors with `convex-codegen`.
4. [Auth](auth.md) — verifying JWTs locally and authenticating the sync
   client.
5. [Storage](storage.md) — uploading and downloading files.
6. [Compose](compose.md) — `QueryState`, `rememberQuery`, and
   `QueryController`.
7. [Values and codec](values-and-codec.md) — `ConvexValue`, the wire
   format, `export()`, identifiers, and the element-level codec.
8. [Example: chat](example-chat.md) — running and reading the Compose
   Desktop sample.

## Conventions used in these guides

- Code samples are Kotlin against the public API; imports are shown once per
  page and omitted afterwards.
- `ConvexValue` is the value type throughout. `mapOf("body" to
  ConvexValue.String("hello"))` is a typical argument object.
- Behavior described here is covered by tests: unit tests per module,
  `conformance/fixtures` replays of recorded backend traffic, and
  `:integration-tests` against the real pinned backend.
