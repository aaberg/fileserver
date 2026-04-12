package net.aabergs.client.privateapi

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FileserverClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: FileserverClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = FileserverClient(
            baseUrl = server.url("/").toString(),
            bearerToken = "test-token"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun healthReturnsTrueForOkStatus() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\"}")
        )

        val healthy = client.health()

        val request = server.takeRequest()
        assertEquals("/health", request.path)
        assertFalse(request.headers.names().contains("Authorization"))
        assertTrue(healthy)
    }

    @Test
    fun healthReturnsFalseForMalformedBody() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("not-json")
        )

        val healthy = client.health()

        assertFalse(healthy)
    }

    @Test
    fun healthReturnsFalseForNonSuccessStatus() {
        server.enqueue(MockResponse().setResponseCode(503))

        val healthy = client.health()

        assertFalse(healthy)
    }

    @Test
    fun uploadSendsAuthHeaderAndBinaryBody() {
        server.enqueue(MockResponse().setResponseCode(200))
        val content = "hello".toByteArray()

        client.uploadFile("report.txt", content)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/file/report.txt", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("application/octet-stream", request.getHeader("Content-Type"))
        assertEquals("hello", request.body.readUtf8())
    }

    @Test
    fun createPublicUrlParsesResponse() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"publicUrl\":\"http://localhost:9000/abc123\"}")
        )

        val publicUrl = client.createPublicUrl("report.txt", 120)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/file/report.txt/public-url", request.path)
        assertContains(request.body.readUtf8(), "\"duration\":120")
        assertEquals("http://localhost:9000/abc123", publicUrl)
    }

    @Test
    fun downloadMaps404ToNotFoundException() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"not_found\",\"message\":\"File not found\"}")
        )

        val exception = assertFailsWith<NotFoundException> {
            client.downloadFile("missing.txt")
        }

        assertEquals(404, exception.statusCode)
        assertEquals("not_found", exception.errorCode)
        assertEquals("File not found", exception.message)
    }

    @Test
    fun uploadMapsUnauthorizedToTypedException() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"unauthorized\",\"message\":\"Unauthorized\"}")
        )

        val exception = assertFailsWith<UnauthorizedException> {
            client.uploadFile("report.txt", "abc".toByteArray())
        }

        assertEquals(401, exception.statusCode)
        assertEquals("unauthorized", exception.errorCode)
    }

    @Test
    fun deleteSendsAuthorizedDeleteRequest() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.deleteFile("report.txt")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/file/report.txt", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun uploadTemporaryFileParsesResponse() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"tempFileId\":\"123e4567-e89b-12d3-a456-426614174000\",\"expiresAt\":1700000000000}")
        )

        val result = client.uploadTemporaryFile("tmp".toByteArray())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/temp-file", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", result.tempFileId)
        assertEquals(1700000000000, result.expiresAt)
    }

    @Test
    fun promoteTemporaryFileUsesExpectedEndpoint() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.promoteTemporaryFile("123e4567-e89b-12d3-a456-426614174000", "final.txt")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/temp-file/123e4567-e89b-12d3-a456-426614174000/promote/final.txt", request.path)
    }

    @Test
    fun createPublicUrlForTemporaryFileParsesResponse() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"publicUrl\":\"http://localhost:9000/temp123\"}")
        )

        val result = client.createPublicUrlForTemporaryFile("123e4567-e89b-12d3-a456-426614174000", 5)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/temp-file/123e4567-e89b-12d3-a456-426614174000/public-url", request.path)
        assertContains(request.body.readUtf8(), "\"duration\":5")
        assertEquals("http://localhost:9000/temp123", result)
    }

    @Test
    fun downloadTemporaryFileReturnsBinaryContent() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody("temporary-content")
        )

        val content = client.downloadTemporaryFile("123e4567-e89b-12d3-a456-426614174000")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/temp-file/123e4567-e89b-12d3-a456-426614174000", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("temporary-content", String(content, Charsets.UTF_8))
    }

    @Test
    fun downloadTemporaryFileMaps404ToNotFoundException() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"not_found\",\"message\":\"Temporary file not found\"}")
        )

        val exception = assertFailsWith<NotFoundException> {
            client.downloadTemporaryFile("missing-temp-id")
        }

        assertEquals(404, exception.statusCode)
        assertEquals("not_found", exception.errorCode)
        assertEquals("Temporary file not found", exception.message)
    }
}
