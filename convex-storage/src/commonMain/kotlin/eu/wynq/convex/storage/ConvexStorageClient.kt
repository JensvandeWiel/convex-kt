/*
 * Copyright 2026 convex-kt contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.wynq.convex.storage

import eu.wynq.convex.core.ConvexException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thrown when a storage transfer fails.
 *
 * @property statusCode the HTTP status, or 0 when the failure predates a
 *   response (for example a malformed body).
 * @property message what went wrong.
 * @property cause the underlying failure, if any.
 */
public class ConvexStorageException(
    message: String,
    public val statusCode: Int,
    cause: Throwable? = null,
) : ConvexException(message, cause)

/**
 * File transfer over Convex storage.
 *
 * This is the only module that speaks plain HTTP, but it deliberately does
 * **not** create URLs: Convex generates upload and download URLs inside
 * functions (`ctx.storage.generateUploadUrl()` and `ctx.storage.getUrl(id)`),
 * because they are signed and short-lived. An app exposes those as a function
 * and calls them through the sync client, then passes the resulting URL here.
 *
 * The flow is therefore:
 * 1. call the app's function to get an upload URL,
 * 2. [upload] the bytes to it and receive a storage id,
 * 3. call the app's function with that id to get a download URL,
 * 4. [download] the bytes from it.
 *
 * @property client the HTTP client used for the transfers.
 */
public class ConvexStorageClient(
    private val client: HttpClient,
) {
    /**
     * Uploads bytes to a pre-signed upload URL.
     *
     * @param uploadUrl a URL from `storage.generateUploadUrl`.
     * @param bytes the file contents.
     * @param contentType the file's content type.
     * @return the new storage id.
     * @throws ConvexStorageException when the request fails.
     */
    public suspend fun upload(
        uploadUrl: String,
        bytes: ByteArray,
        contentType: String = DEFAULT_CONTENT_TYPE,
    ): String {
        val response = client.post(uploadUrl) {
            header(HttpHeaders.ContentType, contentType)
            setBody(bytes)
        }
        return response.bodyJson().string("storageId")
    }

    /**
     * Downloads bytes from a URL returned by `storage.getUrl`.
     *
     * @param fileUrl the signed download URL.
     * @return the file contents.
     * @throws ConvexStorageException when the request fails.
     */
    public suspend fun download(fileUrl: String): ByteArray {
        val response = client.get(fileUrl)
        if (!response.status.isSuccess()) {
            throw ConvexStorageException("download failed: ${response.status}", response.status.value)
        }
        return response.readRawBytes()
    }

    private suspend fun HttpResponse.bodyJson(): JsonObject {
        val text = bodyAsText()
        if (!status.isSuccess()) {
            throw ConvexStorageException("storage request failed: $status $text", status.value)
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: throw ConvexStorageException("response lacks '$key'", 0)

    private companion object {
        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}
