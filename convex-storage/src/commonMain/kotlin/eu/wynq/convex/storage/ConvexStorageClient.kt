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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
 * Thrown when the storage HTTP API rejects a request.
 *
 * @property statusCode the HTTP status, or 0 when the failure predates a
 *   response (for example a malformed body).
 */
public class ConvexStorageException(
    message: String,
    public val statusCode: Int,
    cause: Throwable? = null,
) : ConvexException(message, cause)

/**
 * The file storage HTTP API.
 *
 * This is the only module that speaks plain HTTP: the sync protocol carries
 * function calls, but file bytes move over the deployment's HTTP endpoints.
 * The flow is always `generateUploadUrl` then `upload`, or `download` by
 * storage id; [uploadFile] composes the first two.
 *
 * @property deploymentUrl the deployment origin, for example
 *   `https://example.convex.cloud`.
 * @property client the HTTP client.
 * @property authToken an admin token, sent as `Authorization: Convex <token>`
 *   when present.
 */
public class ConvexStorageClient(
    private val deploymentUrl: String,
    private val client: HttpClient,
    private val authToken: String? = null,
) {
    private val base: String = deploymentUrl.trimEnd('/')

    /**
     * The permanent URL for a stored file.
     *
     * @param storageId the file's storage id.
     * @return the download URL.
     */
    public fun fileUrl(storageId: String): String = "$base/api/storage/$storageId"

    /**
     * Requests a short-lived URL to upload to.
     *
     * @return the upload URL.
     * @throws ConvexStorageException when the request fails.
     */
    public suspend fun generateUploadUrl(): String {
        val response = client.post("$base/api/storage/generate-upload-url") { authorize() }
        return response.bodyJson().string("url")
    }

    /**
     * Uploads bytes to an upload URL from [generateUploadUrl].
     *
     * @param uploadUrl the upload URL.
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
     * Generates an upload URL and uploads in one step.
     *
     * @param bytes the file contents.
     * @param contentType the file's content type.
     * @return the new storage id.
     */
    public suspend fun uploadFile(
        bytes: ByteArray,
        contentType: String = DEFAULT_CONTENT_TYPE,
    ): String = upload(generateUploadUrl(), bytes, contentType)

    /**
     * Downloads a stored file.
     *
     * @param storageId the file's storage id.
     * @return the file contents.
     * @throws ConvexStorageException when the request fails.
     */
    public suspend fun download(storageId: String): ByteArray {
        val response = client.get("$base/api/storage/$storageId")
        if (!response.status.isSuccess()) {
            throw ConvexStorageException("download failed: ${response.status}", response.status.value)
        }
        return response.readRawBytes()
    }

    private fun HttpRequestBuilder.authorize() {
        authToken?.let { header(HttpHeaders.Authorization, "Convex $it") }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.bodyJson(): JsonObject {
        val text = bodyAsText()
        if (!status.isSuccess()) {
            throw ConvexStorageException("storage request failed: $status $text", status.value)
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.string(key: String): String =
        (this[key]?.jsonPrimitive?.content)
            ?: throw ConvexStorageException("response lacks '$key'", 0)

    private companion object {
        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}
