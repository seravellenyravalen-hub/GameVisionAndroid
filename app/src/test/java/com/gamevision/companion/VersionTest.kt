package com.gamevision.companion

import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun appVersionIsNewerThanInitialRelease() {
        assertTrue("versionCode must be greater than 1", BuildConfig.VERSION_CODE > 1)
        assertTrue("versionName must be newer than 1.0.0", BuildConfig.VERSION_NAME != "1.0.0")
    }
}
