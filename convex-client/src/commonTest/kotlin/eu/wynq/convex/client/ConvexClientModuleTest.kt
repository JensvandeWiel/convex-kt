package eu.wynq.convex.client

import kotlin.test.Test
import kotlin.test.assertSame

class ConvexClientModuleTest {
    @Test
    fun markerIsASingleton() {
        assertSame(ConvexClientModule, ConvexClientModule)
    }
}
