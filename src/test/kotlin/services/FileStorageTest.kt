package net.aabergs.services

import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertFailsWith

class FileStorageTest {
    private lateinit var storage: FileStorage
    private val testDir = Files.createTempDirectory("fileserver-test").toString()
    
    @Before
    fun setup() {
        storage = FileStorage(testDir)
    }
    
    @After
    fun cleanup() {
        File(testDir).deleteRecursively()
    }
    
    @Test
    fun testStoreAndRetrieveFile() {
        val testId = "test-file"
        val testContent = "Hello, World!".toByteArray()
        
        // Store file - should not throw exception
        storage.storeFile(testId, testContent)
        
        // Retrieve file
        val retrievedContent = storage.getFile(testId)
        assertNotNull(retrievedContent)
        assertArrayEquals(testContent, retrievedContent)
    }
    
    @Test
    fun testDeleteFile() {
        val testId = "test-file"
        val testContent = "Hello, World!".toByteArray()
        
        // Store and delete file
        storage.storeFile(testId, testContent)
        storage.deleteFile(testId) // Should not throw exception
        
        // Verify file is gone
        val retrievedContent = storage.getFile(testId)
        assertNull(retrievedContent)
    }
    
    @Test
    fun testDeleteNonExistentFile() {
        // Deleting non-existent file should not throw exception
        storage.deleteFile("non-existent-file")
    }
    
    @Test
    fun testGetNonExistentFile() {
        val retrievedContent = storage.getFile("non-existent")
        assertNull(retrievedContent)
    }
    
    @Test
    fun testStoreFileWithInvalidDirectory() {
        try {
            // Creating storage with invalid directory should fail
            val invalidStorage = FileStorage("/invalid/directory/path")
            invalidStorage.storeFile("test-file", "content".toByteArray())
            fail("Expected file system exception to be thrown")
        } catch (e: Exception) {
            // Expected - could be IOException or other file system error
        }
    }

    @Test
    fun testRejectDotAndDotDotIds() {
        assertFailsWith<IllegalArgumentException> {
            storage.storeFile(".", "content".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            storage.storeFile("..", "content".toByteArray())
        }
    }

    @Test
    fun testAllowIdWithDotExtension() {
        val id = "archive.tar.gz"
        val bytes = "content".toByteArray()

        storage.storeFile(id, bytes)
        val retrieved = storage.getFile(id)

        assertNotNull(retrieved)
        assertArrayEquals(bytes, retrieved)
    }
}
