package eu.wynq.convex.storage

import kotlin.test.Test
import kotlin.test.assertSame

class ConvexStorageModuleTest {
    @Test
    fun markerIsASingleton() {
        assertSame(ConvexStorageModule, ConvexStorageModule)
    }
}
