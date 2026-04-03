package net.aabergs.services

import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

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
        
        // Store file
        val result = storage.storeFile(testId, testContent)
        assertTrue(result)
        
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
        val result = storage.deleteFile(testId)
        assertTrue(result)
        
        // Verify file is gone
        val retrievedContent = storage.getFile(testId)
        assertNull(retrievedContent)
    }
    
    @Test
    fun testGetNonExistentFile() {
        val retrievedContent = storage.getFile("non-existent")
        assertNull(retrievedContent)
    }
}