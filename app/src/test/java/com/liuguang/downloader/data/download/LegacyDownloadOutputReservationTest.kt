package com.liuguang.downloader.data.download

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyDownloadOutputReservationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun reservesUniqueNamesWithoutOverwritingExistingFile() {
        val directory = temporaryFolder.newFolder("downloads")
        val existingFile = directory.resolve("same.mp4")
        existingFile.writeText("existing")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)

        try {
            val reservations = List(3) {
                executor.submit<String> {
                    start.await()
                    LegacyDownloadOutputReservation.reserve(directory, "same.mp4").name
                }
            }
            start.countDown()

            assertEquals(
                setOf("same (1).mp4", "same (2).mp4", "same (3).mp4"),
                reservations.map { it.get() }.toSet()
            )
            assertEquals("existing", existingFile.readText())
        } finally {
            executor.shutdownNow()
        }
    }
}
