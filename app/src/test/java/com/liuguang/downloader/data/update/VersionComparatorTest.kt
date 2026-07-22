package com.liuguang.downloader.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test
    fun detectsNewerSemanticVersion() {
        assertTrue(VersionComparator.isNewer("1.0.7", "1.0.6"))
        assertTrue(VersionComparator.isNewer("v1.1.0", "1.0.99"))
        assertTrue(VersionComparator.isNewer("2.0", "1.9.9"))
    }

    @Test
    fun rejectsSameOrOlderVersion() {
        assertFalse(VersionComparator.isNewer("1.0.6", "1.0.6"))
        assertFalse(VersionComparator.isNewer("1.0.5", "1.0.6"))
        assertFalse(VersionComparator.isNewer("1.0", "1.0.0"))
    }
}
