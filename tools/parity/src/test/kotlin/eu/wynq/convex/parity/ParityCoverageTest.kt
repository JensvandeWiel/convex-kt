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
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Enforces the first hard rule from `AGENTS.md` at test time: every upstream
 * `convex-rs` test must be accounted for in `parity.yaml`.
 *
 * This runs as part of `./gradlew check`, so adding an upstream test without a
 * manifest entry fails CI even if nobody runs the standalone parity tool. The
 * manifest and submodule paths are injected by the module's build script.
 */
class ParityCoverageTest {

    @Test
    fun everyUpstreamTestIsAccountedFor() {
        val manifestPath = System.getProperty("convexkt.manifest")
        val upstreamRoot = System.getProperty("convexkt.upstreamRoot")

        assertNotNull(
            manifestPath,
            "convexkt.manifest is not set; run this test via Gradle, not directly.",
        )
        assertNotNull(
            upstreamRoot,
            "convexkt.upstreamRoot is not set; run this test via Gradle, not directly.",
        )

        val report = ParityCoverage.check(File(manifestPath), File(upstreamRoot))
        assertTrue(report.isOk, report.render())
    }
}
