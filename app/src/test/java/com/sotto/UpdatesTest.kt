package com.sotto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version comparison decides whether a phone installs a new build. Wrong in one direction
 * and nobody ever updates; wrong in the other and the app offers the build it is already
 * running, forever.
 */
class UpdatesTest {

    @Test fun tagsBecomeVersions() {
        assertEquals("0.24", Updates.cleanVersion("v0.24"))
        assertEquals("0.24", Updates.cleanVersion("V0.24"))
        assertEquals("0.24", Updates.cleanVersion("  v0.24  "))
        assertEquals("1.2.3", Updates.cleanVersion("v1.2.3"))
        assertEquals("1.2.3.4", Updates.cleanVersion("1.2.3.4"))
    }

    /** Anything that is not dotted digits is refused, so a strange tag cannot drive an install. */
    @Test fun anythingElseIsRefused() {
        for (bad in listOf("", "v", "latest", "0", "v0.24-beta", "0.24.5.6.7", "1..2", "0.x", "../../etc", "0,24")) {
            assertEquals("accepted '$bad'", "", Updates.cleanVersion(bad))
        }
    }

    @Test fun newerIsNewer() {
        assertTrue(Updates.isNewer("0.25", "0.24"))
        assertTrue(Updates.isNewer("1.0", "0.99"))
        assertTrue(Updates.isNewer("0.24.1", "0.24"))
        assertTrue(Updates.isNewer("0.10", "0.9"))       // dotted numbers, not decimals
        assertTrue(Updates.isNewer("2.0", "1.9.9.9"))
    }

    @Test fun theSameOrOlderIsNot() {
        assertFalse(Updates.isNewer("0.24", "0.24"))
        assertFalse(Updates.isNewer("0.24", "0.24.0"))   // trailing zeros are the same version
        assertFalse(Updates.isNewer("0.24.0", "0.24"))
        assertFalse(Updates.isNewer("0.23", "0.24"))
        assertFalse(Updates.isNewer("0.9", "0.10"))
        assertFalse(Updates.isNewer("1.9.9", "2.0"))
    }

    /** The running build's own version string must be one this comparison understands. */
    @Test fun theShippingVersionParses() {
        val v = Updates.cleanVersion(BuildConfig.VERSION_NAME)
        assertTrue("BuildConfig.VERSION_NAME '${BuildConfig.VERSION_NAME}' is not a dotted version", v.isNotEmpty())
        assertFalse("the running build offers itself as an update", Updates.isNewer(v, BuildConfig.VERSION_NAME))
    }

    /** Only an asset on our own release page is ever fetched. */
    @Test fun theEvergreenLinkPointsAtOurRelease() {
        assertTrue(Updates.EVERGREEN_APK.startsWith("https://github.com/retrocodes12/sotto/releases/"))
        assertTrue("the in-app updater depends on the asset being named sotto.apk", Updates.EVERGREEN_APK.endsWith("/sotto.apk"))
    }
}
