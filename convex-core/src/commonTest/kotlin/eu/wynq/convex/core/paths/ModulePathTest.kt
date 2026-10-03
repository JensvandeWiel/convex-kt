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
package eu.wynq.convex.core.paths

import eu.wynq.convex.core.ConvexException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers module-path parsing and classification.
 *
 * Mirrors the upstream `convex-rs` `sync_types/src/module_path.rs::test_module_path`
 * vectors one for one.
 */
class ModulePathTest {

    @Test
    fun testModulePath() {
        val systemPaths = listOf("_system", "_system/retrograde", "_system/say/what/you/will")
        for (path in systemPaths) {
            assertTrue(ModulePath.parse(path).isSystem(), "expected system: $path")
        }
        val errors = listOf("", "/dont/miss/it", "wilhelm/../scream", "i'llcometoo.mp3")
        for (path in errors) {
            assertFailsWith<ConvexException>("expected parse to fail for '$path'") {
                ModulePath.parse(path)
            }
        }
        val notSystemPaths = listOf("toxicity", "byob.js", "hypnotize/lonelyday")
        for (path in notSystemPaths) {
            assertFalse(ModulePath.parse(path).isSystem(), "expected not system: $path")
        }
        val depsPaths = listOf("_deps", "_deps/whoo.js", "actions/_deps/whoo.js")
        for (path in depsPaths) {
            assertTrue(ModulePath.parse(path).isDeps(), "expected deps: $path")
        }
        val httpPaths = listOf("http.js")
        for (path in httpPaths) {
            assertTrue(ModulePath.parse(path).isHttp(), "expected http: $path")
        }
        val notHttpPaths = listOf("foo/http.js", "actions/http.js", "_deps/http.js", "_system/http.js")
        for (path in notHttpPaths) {
            assertFalse(ModulePath.parse(path).isHttp(), "expected not http: $path")
        }
    }
}
