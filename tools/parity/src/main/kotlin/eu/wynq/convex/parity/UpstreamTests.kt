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

/**
 * A single test function discovered in the upstream `convex-rs` sources.
 *
 * The reference format matches what `parity.yaml` stores so discovery and the
 * manifest can be compared directly, without a second mapping layer that could
 * disagree with the first.
 *
 * @property path path relative to the upstream checkout, using `/` separators.
 * @property name the Rust function name carrying the test attribute.
 */
public data class UpstreamTest(
    public val path: String,
    public val name: String,
) {
    /** Canonical manifest reference, `<relative/path>::<name>`. */
    public val reference: String get() = "$path::$name"
}

/**
 * Discovers Rust test functions in a `convex-rs` checkout.
 *
 * Discovery is deliberately textual rather than driven by `cargo test`: it must
 * run in CI without a Rust toolchain, and it only needs test *names*, not their
 * results. Both `#[test]` and `#[tokio::test]` are recognized; proptest cases
 * declare `#[test]` inside a `proptest!` block and are therefore found too.
 *
 * A test attribute is followed by its function, possibly after further
 * attributes, comments, or blank lines, so the scan skips those before reading
 * the name.
 */
public object RustTestDiscovery {
    private val TEST_ATTRIBUTE = Regex("""^\s*#\[\s*(?:tokio::)?test\b""")
    private val FUNCTION = Regex("""^\s*(?:pub\s+)?(?:async\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val ATTRIBUTE = Regex("""^\s*#!?\[""")
    private val COMMENT = Regex("""^\s*//""")
    private const val LOOKAHEAD_LINES = 8

    /**
     * Scans [root] for test functions.
     *
     * @param root the upstream checkout (for example `third_party/convex-rs`).
     * @return every discovered test, sorted by path then name.
     * @throws IllegalArgumentException when [root] is not a directory, so a
     *   missing submodule fails loudly instead of yielding an empty set that
     *   would make the coverage gate vacuously pass.
     */
    public fun discover(root: File): List<UpstreamTest> {
        require(root.isDirectory) { "upstream root is not a directory: ${root.path}" }
        val found = sortedSetOf(compareBy(UpstreamTest::path, UpstreamTest::name))
        root.walkTopDown()
            .onEnter { it.name != "target" && it.name != ".git" }
            .filter { it.isFile && it.extension == "rs" }
            .forEach { file ->
                val relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                collect(file.readLines(), relative, found)
            }
        return found.toList()
    }

    private fun collect(lines: List<String>, relative: String, into: MutableSet<UpstreamTest>) {
        lines.forEachIndexed { index, line ->
            if (TEST_ATTRIBUTE.containsMatchIn(line)) {
                nextFunctionName(lines, index + 1)?.let { into += UpstreamTest(relative, it) }
            }
        }
    }

    private fun nextFunctionName(lines: List<String>, start: Int): String? {
        val end = minOf(start + LOOKAHEAD_LINES, lines.size)
        for (index in start until end) {
            val line = lines[index]
            when {
                line.isBlank() -> Unit
                ATTRIBUTE.containsMatchIn(line) -> Unit
                COMMENT.containsMatchIn(line) -> Unit
                else -> return FUNCTION.find(line)?.groupValues?.get(1)
            }
        }
        return null
    }
}
