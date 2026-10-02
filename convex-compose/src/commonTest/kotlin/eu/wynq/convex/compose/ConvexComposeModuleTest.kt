package eu.wynq.convex.compose

import kotlin.test.Test
import kotlin.test.assertSame

class ConvexComposeModuleTest {
    @Test
    fun markerIsASingleton() {
        assertSame(ConvexComposeModule, ConvexComposeModule)
    }
}
