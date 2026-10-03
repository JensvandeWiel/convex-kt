# Storage

`convex-storage` moves file bytes over plain HTTPS. It never invents URLs:
your Convex functions mint upload and download URLs, and this module
transfers through them. That split keeps access control server-side, where
it belongs.

## The four steps

```kotlin
import eu.wynq.convex.storage.ConvexStorageClient
import io.ktor.client.HttpClient

val storage = ConvexStorageClient(HttpClient())

// 1. Your function mints an upload URL (via the sync client or HTTP API):
//    storage.generateUploadUrl -> "https://…"
// 2. Upload the bytes; the response is the storage id:
val storageId: String = storage.upload(uploadUrl, bytes, "image/png")
// 3. Your function resolves the id to a download URL:
//    storage.getFileUrl(storageId) -> "https://…"
// 4. Download:
val bytes: ByteArray = storage.download(fileUrl)
```

`upload` defaults to `application/octet-stream` when no content type fits.
`download` returns raw bytes — decode images, write files, or hand them to
platform APIs yourself.

## Errors

Every failure throws `ConvexStorageException`, which carries the HTTP
`statusCode` — or 0 when the failure predates a usable response, for
example a 200 whose body is not a JSON object. The underlying parser
failure is preserved as `cause`, never leaked as its own type, so callers
catch one exception for the whole module.
