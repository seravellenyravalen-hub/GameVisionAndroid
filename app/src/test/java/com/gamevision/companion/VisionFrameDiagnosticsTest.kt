package com.gamevision.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionFrameDiagnosticsTest {
    @Test fun classifiesMissingFrame() {
        assertEquals("NO CAPTURE", VisionFrameDiagnostics.classify(0, -1L, false))
    }

    @Test fun classifiesFreshFrame() {
        assertEquals("VISION READY", VisionFrameDiagnostics.classify(42, 180L, true))
    }

    @Test fun classifiesStaleFrame() {
        assertEquals("FRAME STALE", VisionFrameDiagnostics.classify(42, 16000L, false))
    }

    @Test fun classifiesFrozenFrame() {
        assertEquals("FRAME FROZEN", VisionFrameDiagnostics.classify(42, 200L, true, true))
    }

    @Test fun tapProofSeparatesDispatchFromVerification() {
        assertEquals("DISPATCHED", VisionFrameDiagnostics.commandProof(true, false))
        assertEquals("VERIFIED", VisionFrameDiagnostics.commandProof(true, true))
        assertEquals("FAILED", VisionFrameDiagnostics.commandProof(false, false))
        assertTrue(VisionFrameDiagnostics.commandProof(true, false) != "VERIFIED")
    }
}
