package net.aabergs.services

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TemporaryFileStorageTest {
    private lateinit var temporaryStorage: TemporaryFileStorage
    private lateinit var fileStorage: FileStorage
    private val testDir = Files.createTempDirectory("fileserver-temp-test").toString()

    @Before
    fun setup() {
        temporaryStorage = TemporaryFileStorage(testDir)
        fileStorage = FileStorage(testDir)
    }

    @After
    fun cleanup() {
        File(testDir).deleteRecursively()
    }

    @Test
    fun storeAndReadTemporaryFile() {
        val info = temporaryStorage.storeTemporaryFromStream("content".byteInputStream(), 1024, 300)
        val path = temporaryStorage.getTemporaryFilePathIfValid(info.tempFileId)

        assertNotNull(path)
        assertEquals("content", Files.readString(path))
    }

    @Test
    fun promoteTemporaryFileMovesContentToPermanentStorage() {
        val info = temporaryStorage.storeTemporaryFromStream("content".byteInputStream(), 1024, 300)
        val destination = fileStorage.getDestinationPath("final.txt")

        temporaryStorage.promoteTemporaryFile(info.tempFileId, destination)

        assertEquals("content", fileStorage.getFile("final.txt")?.toString(Charsets.UTF_8))
        assertNull(temporaryStorage.getTemporaryFileInfo(info.tempFileId))
    }
}
