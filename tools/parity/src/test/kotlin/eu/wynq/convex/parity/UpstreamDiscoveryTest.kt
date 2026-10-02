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
package eu.wynq.convex.parity

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the upstream test discovery and the manifest-generation round trip that
 * the parity gate relies on. If discovery silently misses test kinds, the gate
 * would pass while ignoring them, which is the failure mode this guards against.
 */
class UpstreamDiscoveryTest {

    @Test
    fun discoversTestTokioTestAndProptestCases() {
        val root = tempDir()
        write(
            root,
            "sample.rs",
            """
            #[test]
            fn plain() {}

            #[tokio::test]
            async fn async_one() {}

            #[test]
            #[ignore]
            fn with_extra_attribute() {}

            mod tests {
                use super::*;

                proptest! {
                    #[test]
                    fn prop_case(x in any::<u64>()) {}
                }
            }

            #[test]
            fn after_block() {}
            """.trimIndent(),
        )

        val names = RustTestDiscovery.discover(root).map { it.name }.sorted()

        assertEquals(
            listOf("after_block", "async_one", "plain", "prop_case", "with_extra_attribute"),
            names,
        )
    }

    @Test
    fun missingSubmoduleFailsLoudly() {
        val missing = File(tempDir(), "does-not-exist")
        val failure = runCatching { RustTestDiscovery.discover(missing) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a clear failure, got $failure")
    }

    @Test
    fun emittedEntriesMakeAnEmptyManifestComplete() {
        val upstream = tempDir()
        write(
            upstream,
            "sync/lib.rs",
            """
            #[test]
            fn alpha() {}

            #[test]
            fn beta() {}
            """.trimIndent(),
        )
        val manifest = File(tempDir(), "parity.yaml")
        manifest.writeText("schemaVersion: 1\nrequirements:\n")

        val before = ParityCoverage.check(manifest, upstream)
        assertFalse(before.isOk, "coverage should fail before the entries are added")
        assertEquals(2, before.missing.size)

        manifest.appendText(ParityCoverage.emitMissing(before))

        val after = ParityCoverage.check(manifest, upstream)
        assertTrue(after.isOk, after.render())
    }

    private fun tempDir(): File = createTempDirectory("convex-parity").toFile()

    private fun write(root: File, relative: String, content: String) {
        val file = File(root, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
