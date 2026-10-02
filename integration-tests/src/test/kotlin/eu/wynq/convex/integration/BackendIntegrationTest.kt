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
package eu.wynq.convex.integration

import eu.wynq.convex.client.AuthTokenFetcher
import eu.wynq.convex.client.ConvexHttpApi
import eu.wynq.convex.client.ConvexSyncClient
import eu.wynq.convex.client.KtorSyncProtocolFactory
import eu.wynq.convex.client.syncUrl
import eu.wynq.convex.core.protocol.AuthenticationToken
import eu.wynq.convex.core.protocol.ConvexResult
import eu.wynq.convex.core.value.ConvexValue
import eu.wynq.convex.storage.ConvexStorageClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end tests against a real, pinned Convex backend started with
 * Testcontainers.
 *
 * This is the concrete form of the "Real backend integration" hard rule: no
 * mocked server responses. The backend image is the same commit the conformance
 * fixtures were recorded from, and the conformance project's functions are
 * deployed into it via the Convex CLI.
 *
 * Run with `./gradlew :integration-tests:integrationTest` (needs Docker and
 * Node). It is deliberately not part of `build`.
 */
class BackendIntegrationTest {
    @Test
    fun subscriptionAndMutationUpdateTheList() = withSyncClient { client, _ ->
        val subscriber = client.subscribe("messages:list")
        val queryId = subscriber.queryId
        awaitUntil("the first query result") {
            client.results.value[queryId] is ConvexResult.Success
        }

        val result = client.mutate("messages:send", mapOf("body" to ConvexValue.String("hello")))
        assertIs<ConvexResult.Success>(result)
        awaitUntil("the mutation to appear in the subscription") {
            val success = client.results.value[queryId] as? ConvexResult.Success ?: return@awaitUntil false
            containsBody(success.value, "hello")
        }
    }

    @Test
    fun actionReturnsItsValue() = withSyncClient { client, _ ->
        val result = client.action("messages:echo", mapOf("body" to ConvexValue.String("ping")))
        assertEquals(ConvexResult.Success(ConvexValue.String("ping")), result)
    }

    @Test
    fun optimisticUpdateIsVisibleBeforeTheServerConfirms() {
        withSyncClient { client, scope ->
            val subscriber = client.subscribe("messages:list")
            val queryId = subscriber.queryId
            awaitUntil("the first query result") {
                client.results.value[queryId] is ConvexResult.Success
            }

            // `slowEcho` takes a second, so the prediction is observable.
            val call = scope.async {
                client.action("messages:slowEcho", mapOf("body" to ConvexValue.String("optimistic"))) { results ->
                    results + (queryId to ConvexResult.Success(ConvexValue.String("optimistic-value")))
                }
            }
            awaitUntil("the optimistic value") {
                client.results.value[queryId] == ConvexResult.Success(ConvexValue.String("optimistic-value"))
            }
            assertIs<ConvexResult.Success>(call.await())
            awaitUntil("the prediction to be dropped after acknowledgement") {
                client.results.value[queryId] != ConvexResult.Success(ConvexValue.String("optimistic-value"))
            }
        }
    }

    @Test
    fun adminAuthConnectsAndQueries() {
        withSyncClient(
            authFetcher = AuthTokenFetcher { AuthenticationToken.Admin(adminKey) },
        ) { client, _ ->
            val result = client.mutate("messages:send", mapOf("body" to ConvexValue.String("authed")))
            assertIs<ConvexResult.Success>(result)
        }
    }

    @Test
    fun storageRoundTrip() = withSyncClient { client, _ ->
        val payload = "convex-kt integration payload".encodeToByteArray()
        val http = HttpClient(OkHttp)

        val uploadUrl = uploadUrlFrom(client)
        val storage = ConvexStorageClient(http)
        val storageId = storage.upload(rewriteToHost(uploadUrl), payload)

        val fileUrlResult = ConvexHttpApi(baseUrl(), http).query(
            path = "storage:getFileUrl",
            args = mapOf("storageId" to ConvexValue.String(storageId)),
        )
        val fileUrl = assertIs<ConvexValue.String>(
            assertIs<ConvexResult.Success>(fileUrlResult).value,
        ).value

        assertContentEquals(payload, storage.download(rewriteToHost(fileUrl)))
    }

    @Test
    fun httpFunctionsApiQueries() {
        withSyncClient { _, _ ->
            val result = ConvexHttpApi(baseUrl(), HttpClient(OkHttp)).query("messages:list")
            assertIs<ConvexResult.Success>(result)
        }
    }

    private suspend fun uploadUrlFrom(client: ConvexSyncClient): String {
        val result = client.mutate("storage:generateUploadUrl", emptyMap())
        return assertIs<ConvexValue.String>(assertIs<ConvexResult.Success>(result).value).value
    }

    private fun containsBody(value: ConvexValue, body: String): Boolean =
        value is ConvexValue.Array &&
            value.value.any { element ->
                val fields = (element as? ConvexValue.Object)?.value ?: return@any false
                (fields["body"] as? ConvexValue.String)?.value == body
            }

    /** Rewrites a backend-issued URL onto the Testcontainers host port. */
    private fun rewriteToHost(url: String): String {
        val uri = URI(url)
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "http://127.0.0.1:$backendPort${uri.rawPath}$query"
    }

    // The real dispatcher is the point: these tests do real network I/O with
    // real time, so a virtual/time-controlled dispatcher would defeat them.
    @Suppress("InjectDispatcher")
    private fun <T> withSyncClient(
        authFetcher: AuthTokenFetcher? = null,
        block: suspend (ConvexSyncClient, CoroutineScope) -> T,
    ): T = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val client = ConvexSyncClient(
            factory = KtorSyncProtocolFactory(syncUrl(baseUrl())),
            scope = scope,
            authFetcher = authFetcher,
        )
        client.connect()
        try {
            block(client, scope)
        } finally {
            client.close()
            scope.cancel()
        }
    }

    private suspend fun awaitUntil(
        what: String,
        timeoutMillis: Long = AWAIT_TIMEOUT_MILLIS,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            delay(AWAIT_STEP_MILLIS)
        }
        fail("timed out waiting for $what")
    }

    companion object {
        private const val IMAGE =
            "ghcr.io/get-convex/convex-backend:d2ca8533c52e2dbd8e55e20181988973dcbabe41"
        private const val BACKEND_PORT = 3210
        private const val AWAIT_TIMEOUT_MILLIS = 20_000L
        private const val AWAIT_STEP_MILLIS = 100L
        private const val NANOS_PER_MILLI = 1_000_000L

        private lateinit var container: GenericContainer<*>
        private var backendPort: Int = 0
        private lateinit var adminKey: String

        private fun baseUrl(): String = "http://127.0.0.1:$backendPort"

        @JvmStatic
        @BeforeClass
        fun startBackend() {
            // Builder methods return SELF, so they are called as statements
            // rather than chained: chaining on `GenericContainer<Nothing>`
            // yields Nothing and would not resolve.
            val started = GenericContainer<Nothing>(DockerImageName.parse(IMAGE))
            started.withExposedPorts(BACKEND_PORT)
            started.withEnv("DISABLE_METRICS_ENDPOINT", "true")
            started.withEnv("RUST_LOG", "info")
            started.waitingFor(Wait.forHttp("/version").forPort(BACKEND_PORT).forStatusCode(200))
            started.start()
            container = started
            backendPort = container.getMappedPort(BACKEND_PORT)
            adminKey = readAdminKey()
            deployConformanceProject()
        }

        @JvmStatic
        @AfterClass
        fun stopBackend() {
            container.stop()
        }

        private fun readAdminKey(): String {
            val result = container.execInContainer("./generate_admin_key.sh")
            return result.stdout.lineSequence()
                .firstOrNull { it.startsWith("convex-self-hosted|") }
                ?: error("no admin key in output:\n${result.stdout}\n${result.stderr}")
        }

        private fun deployConformanceProject() {
            val project = File(
                System.getProperty("convexkt.conformanceProject")
                    ?: error("convexkt.conformanceProject is not set; run via Gradle"),
            )
            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val npx = if (windows) "npx.cmd" else "npx"
            val process = ProcessBuilder(npx, "--no-install", "convex", "deploy", "-y")
                .directory(project)
                .redirectErrorStream(true)
                .apply {
                    environment()["CONVEX_SELF_HOSTED_URL"] = baseUrl()
                    environment()["CONVEX_SELF_HOSTED_ADMIN_KEY"] = adminKey
                }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            check(exit == 0) { "convex deploy failed ($exit):\n$output" }
            assertTrue(output.contains("Deployed"), "unexpected deploy output:\n$output")
        }
    }
}
