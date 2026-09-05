package com.gamevision.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVisionPolicyTest {
    @Test
    fun captureCadenceTargetsLiveVision() {
        val policy = LiveVisionPolicy()
        assertEquals(1000L / 12L, policy.captureIntervalMs)
    }

    @Test
    fun unchangedFramesAreNotUploadedUntilHeartbeat() {
        val policy = LiveVisionPolicy(uploadIntervalMs = 200L, heartbeatMs = 1000L)
        assertTrue(policy.shouldUpload("same", 0L))
        assertTrue(!policy.shouldUpload("same", 200L))
        assertTrue(policy.shouldUpload("same", 1000L))
    }

    @Test
    fun changedFramesUploadImmediatelyWithinLiveBudget() {
        val policy = LiveVisionPolicy(uploadIntervalMs = 200L, heartbeatMs = 1000L)
        assertTrue(policy.shouldUpload("a", 0L))
        assertTrue(policy.shouldUpload("b", 50L))
    }
}
