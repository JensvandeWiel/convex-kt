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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParityCheckerTest {

    @Test
    fun acceptsAValidManifest() {
        val report = check(
            """
            schemaVersion: 1
            requirements:
              - id: sync/handshake
                description: Client and server agree on the protocol version.
                status: ported
                upstream:
                  - convex-sync::handshake
                kotlin:
                  - convex-client.HandshakeTest.handshakeCompletes
            """.trimIndent(),
        )

        assertTrue(report.isOk, report.render())
        assertEquals(1, report.requirements.size)
        assertEquals(ParityStatus.PORTED, report.requirements.single().status)
    }

    @Test
    fun rejectsAnUnknownStatus() {
        val report = check(
            """
            schemaVersion: 1
            requirements:
              - id: sync/handshake
                status: done
                upstream: [a::b]
            """.trimIndent(),
        )

        assertFalse(report.isOk)
        assertTrue(report.errors.any { "unknown status" in it }, report.render())
    }

    @Test
    fun rejectsDuplicateIds() {
        val report = check(
            """
            schemaVersion: 1
            requirements:
              - id: sync/handshake
                status: planned
                upstream: [a::b]
              - id: sync/handshake
                status: planned
                upstream: [c::d]
            """.trimIndent(),
        )

        assertFalse(report.isOk)
        assertTrue(report.errors.any { "duplicate id" in it }, report.render())
    }

    @Test
    fun requiresBothSidesForPortedEntries() {
        val report = check(
            """
            schemaVersion: 1
            requirements:
              - id: sync/handshake
                status: ported
                upstream: [a::b]
            """.trimIndent(),
        )

        assertFalse(report.isOk)
        assertTrue(report.errors.any { "need a 'kotlin' reference" in it }, report.render())
    }

    @Test
    fun reportsAMissingFile() {
        val report = ParityChecker.check(File("does-not-exist/parity.yaml"))
        assertFalse(report.isOk)
        assertTrue(report.errors.any { "manifest not found" in it })
    }

    @Test
    fun warnsButPassesOnAnEmptyManifest() {
        val report = check("schemaVersion: 1\nrequirements: []\n")
        assertTrue(report.isOk, report.render())
        assertTrue(report.warnings.any { "empty" in it }, report.render())
    }

    private fun check(yaml: String): ParityReport {
        val file = File.createTempFile("parity", ".yaml")
        file.deleteOnExit()
        file.writeText(yaml)
        return ParityChecker.check(file)
    }
}
