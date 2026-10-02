package eu.wynq.convex.core

import kotlin.test.Test
import kotlin.test.assertSame

class ConvexCoreModuleTest {
    @Test
    fun markerIsASingleton() {
        assertSame(ConvexCoreModule, ConvexCoreModule)
    }
}
