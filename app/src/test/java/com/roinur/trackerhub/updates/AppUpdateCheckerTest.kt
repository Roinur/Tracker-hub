package com.roinur.trackerhub.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun isNewerVersionComparesSemanticVersionsCorrectly() {
        assertFalse(AppUpdateChecker.isNewerVersion("2.1", "v2.1.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.2", "v1.2"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.2.0", "v1.2"))
        assertFalse(AppUpdateChecker.isNewerVersion("2.2", "v2.1.9"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.2-debug", "v1.2"))

        assertTrue(AppUpdateChecker.isNewerVersion("2.1", "v2.1.1"))
        assertTrue(AppUpdateChecker.isNewerVersion("1.1", "v1.2"))
        assertTrue(AppUpdateChecker.isNewerVersion("1.2", "v1.10"))
        assertTrue(AppUpdateChecker.isNewerVersion("0.1.22", "v0.1.23"))
        assertTrue(AppUpdateChecker.isNewerVersion("1.2-debug", "v1.3"))

        assertFalse(AppUpdateChecker.isNewerVersion(null, "v1.2"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.0", null))
        assertFalse(AppUpdateChecker.isNewerVersion("invalid", "v1.2"))
    }

    @Test
    fun extractTagFromReleaseUrlParsesGitHubRedirects() {
        assertEquals(
            "v1.2",
            AppUpdateChecker.extractTagFromReleaseUrl("https://github.com/Roinur/Book-tracker/releases/tag/v1.2")
        )
        assertEquals(
            "v2.1.0",
            AppUpdateChecker.extractTagFromReleaseUrl("/Roinur/Sauce-tracker/releases/tag/v2.1.0?foo=bar")
        )
        assertNull(AppUpdateChecker.extractTagFromReleaseUrl("https://github.com/Roinur/Book-tracker/releases"))
    }
}
