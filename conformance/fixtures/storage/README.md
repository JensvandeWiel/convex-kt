# storage

Proves the storage transfer flow against the pinned backend: an upload URL
minted by a function, a byte upload, a signed download URL, and a byte
download.

Storage URLs are **not** client HTTP endpoints. Convex generates them inside
functions (`ctx.storage.generateUploadUrl()`, `ctx.storage.getUrl(id)`), so the
conformance project exposes `storage:generateUploadUrl` and `storage:getFileUrl`
and the recorder calls them through the HTTP functions API.

## Recorded exchange

`record-storage.mjs`:

1. `POST /api/mutation {path: "storage:generateUploadUrl"}` -> a pre-signed URL.
2. `POST <uploadUrl>` with a known payload -> `{"storageId": "..."}`.
3. `POST /api/query {path: "storage:getFileUrl", args: {storageId}}` -> an
   absolute signed URL.
4. `GET <fileUrl>` -> the payload bytes.

This corrected an earlier assumption in the client, which had treated URL
generation as a direct HTTP endpoint. It is not.

## Fixture

`exchange.json` records the response shapes only. The storage id and the signed
URLs vary per run and are normalized (`storageId` to `<STORAGE_ID>`), so the
file is byte-stable.

`convex-storage`'s JVM `StorageFixtureTest` replays the recorded upload response
and payload through a mock engine, so the client's parsing is fixture-proven
rather than only hand-written.

## Re-record

```bash
docker compose -f conformance/docker-compose.yml up -d --force-recreate
docker compose -f conformance/docker-compose.yml exec backend ./generate_admin_key.sh
cd conformance/harness && npm ci
node record-storage.mjs --url http://127.0.0.1:3210 --admin-key <key>
```
