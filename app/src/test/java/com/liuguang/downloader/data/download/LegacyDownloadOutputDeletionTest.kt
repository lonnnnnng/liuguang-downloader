package com.liuguang.downloader.data.download

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyDownloadOutputDeletionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deletesOnlyTheRequestedFile() {
        val directory = temporaryFolder.newFolder("downloads")
        val target = directory.resolve("video.mp4").apply { writeText("target") }
        val neighbor = directory.resolve("video (1).mp4").apply { writeText("keep") }

        LegacyDownloadOutputDeletion.delete(target, directory)

        assertFalse(target.exists())
        assertEquals("keep", neighbor.readText())
        assertTrue(directory.isDirectory)
    }

    @Test
    fun alreadyMissingFileCanBeRemovedFromHistory() {
        val directory = temporaryFolder.newFolder("downloads")

        LegacyDownloadOutputDeletion.delete(directory.resolve("missing.mp4"), directory)

        assertTrue(directory.isDirectory)
    }

    @Test
    fun neverDeletesADirectory() {
        val directory = temporaryFolder.newFolder("downloads")
        val subdirectory = directory.resolve("video.mp4").apply { mkdir() }

        assertThrows(IllegalStateException::class.java) {
            LegacyDownloadOutputDeletion.delete(subdirectory, directory)
        }

        assertTrue(subdirectory.isDirectory)
    }

    @Test
    fun neverDeletesFilesOutsideTheDownloadsDirectory() {
        val directory = temporaryFolder.newFolder("downloads")
        val outside = temporaryFolder.newFile("outside.mp4").apply { writeText("keep") }

        assertThrows(IllegalStateException::class.java) {
            LegacyDownloadOutputDeletion.delete(outside, directory)
        }

        assertEquals("keep", outside.readText())
    }

    @Test
    fun rejectsSymbolicLinksToFilesOutsideTheDownloadsDirectory() {
        val directory = temporaryFolder.newFolder("downloads")
        val outside = temporaryFolder.newFile("outside.mp4").apply { writeText("keep") }
        val link = directory.resolve("video.mp4")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertThrows(IllegalStateException::class.java) {
            LegacyDownloadOutputDeletion.delete(link, directory)
        }

        assertEquals("keep", outside.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun deletesASymbolicLinkWithoutDeletingItsNeighboringTarget() {
        val directory = temporaryFolder.newFolder("downloads")
        val neighbor = directory.resolve("keep.mp4").apply { writeText("keep") }
        val link = directory.resolve("video.mp4")
        Files.createSymbolicLink(link.toPath(), neighbor.toPath())

        LegacyDownloadOutputDeletion.delete(link, directory)

        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertEquals("keep", neighbor.readText())
    }
}
