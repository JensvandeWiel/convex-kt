package eu.wynq.convex.auth

import kotlin.test.Test
import kotlin.test.assertSame

class ConvexAuthModuleTest {
    @Test
    fun markerIsASingleton() {
        assertSame(ConvexAuthModule, ConvexAuthModule)
    }
}
