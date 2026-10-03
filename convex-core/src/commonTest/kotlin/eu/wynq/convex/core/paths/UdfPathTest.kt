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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers UDF-path parsing, canonicalization, and stripping.
 *
 * Mirrors the upstream `convex-rs` `sync_types/src/udf_path.rs` tests. Upstream
 * proves the round trip with proptest over arbitrary paths; Kotlin has no
 * property runner, so the same property is asserted over a fixed corpus that
 * spans named and default exports, `.js` and extension-less modules, and the
 * `_system`/`_deps`/`actions` prefixes.
 */
class UdfPathTest {

    @Test
    fun testUdfPathRoundtrips() {
        val corpus = listOf(
            "test",
            "test.js",
            "test:function",
            "test.js:function",
            "_system/foo",
            "_system/foo:bar",
            "hypnotize/lonelyday:baz",
            "_deps/whoo.js:qux",
            "actions/_deps/whoo.js:qux",
        )
        for (text in corpus) {
            val parsed = UdfPath.parse(text)
            assertEquals(parsed, UdfPath.parse(parsed.toString()), "round trip failed for $text")
        }
    }

    @Test
    fun testStrip() {
        val defaultExport = UdfPath.parse("test")
        val canonicalized = defaultExport.canonicalize()
        assertEquals("test.js:default", canonicalized.toString())
        assertEquals("test", canonicalized.strip().toString())

        val named = UdfPath.parse("test.js:function")
        val canonicalizedNamed = named.canonicalize()
        assertEquals("test.js:function", canonicalizedNamed.toString())
        assertEquals("test:function", canonicalizedNamed.strip().toString())
    }
}
