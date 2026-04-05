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
}
