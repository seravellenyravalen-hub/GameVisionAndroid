package com.gamevision.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastCommandRouterTest {
    @Test fun basicNavigationIsDeterministic() {
        assertEquals("BACK", FastCommandRouter.parse("go back")?.type)
        assertEquals("HOME", FastCommandRouter.parse("home")?.type)
        assertEquals("RECENTS", FastCommandRouter.parse("open recent apps")?.type)
        assertEquals("NOTIFICATIONS", FastCommandRouter.parse("notifications")?.type)
        assertEquals("QUICK_SETTINGS", FastCommandRouter.parse("open quick settings")?.type)
    }

    @Test fun typingAndWaitStayLocal() {
        val type = FastCommandRouter.parse("type hello world")
        assertEquals("TYPE_TEXT", type?.type)
        assertEquals("hello world", type?.text)
        assertEquals(500L, FastCommandRouter.parse("wait 500 ms")?.waitMs)
    }

    @Test fun complexRequestsRemainOnAiPath() {
        assertNull(FastCommandRouter.parse("find the cheapest flight and book it"))
        assertNull(FastCommandRouter.parse("what is 25 percent of 480"))
    }
}
