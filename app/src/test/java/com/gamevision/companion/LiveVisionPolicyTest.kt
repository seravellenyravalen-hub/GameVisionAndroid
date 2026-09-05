package com.gamevision.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVisionPolicyTest {
    @Test
    fun captureCadenceTargetsLiveVision() {
        val policy = LiveVisionPolicy()
        assertEquals(1000L / 12L, policy.captureIntervalMs)
        assertTrue(policy.shouldCapture(0L))
        policy.markCaptured(0L)
        assertFalse(policy.shouldCapture(50L))
        assertTrue(policy.shouldCapture(1000L / 12L))
    }

    @Test
    fun unchangedFramesAreNotUploadedUntilHeartbeat() {
        val policy = LiveVisionPolicy(uploadIntervalMs = 200L, heartbeatMs = 1000L)
        assertTrue(policy.shouldUpload("same", 0L))
        assertFalse(policy.shouldUpload("same", 200L))
        assertTrue(policy.shouldUpload("same", 1000L))
    }

    @Test
    fun changedFramesRespectUploadCadenceInsteadOfFloodingNetwork() {
        val policy = LiveVisionPolicy(uploadIntervalMs = 200L, heartbeatMs = 1000L)
        assertTrue(policy.shouldUpload("a", 0L))
        assertFalse(policy.shouldUpload("b", 50L))
        assertTrue(policy.shouldUpload("b", 200L))
    }
}
