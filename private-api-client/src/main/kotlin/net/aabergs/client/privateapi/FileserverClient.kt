package net.aabergs.client.privateapi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.aabergs.client.privateapi.dto.CreatePublicUrlRequest
import net.aabergs.client.privateapi.dto.CreatePublicUrlResponse
import net.aabergs.client.privateapi.dto.TemporaryFileUploadResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_UPLOAD_CONTENT_TYPE = "application/octet-stream"

class FileserverClient(
    baseUrl: String,
    private val bearerToken: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
    }
) {
    companion object {
        private val mediaTypeCache: ConcurrentHashMap<String, MediaType> = ConcurrentHashMap()

        private fun getMediaTypeInternal(contentType: String): MediaType? {
            return mediaTypeCache.getOrPut(contentType) { contentType.toMediaType() }
        }
    }

    private val baseHttpUrl: HttpUrl = baseUrl.toHttpUrl()

    fun health(): Boolean {
        val request = Request.Builder()
            .url(url("health"))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return false
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return false
            }

            return runCatching {
                json.decodeFromString(HealthResponse.serializer(), body).status == "ok"
            }.getOrDefault(false)
        }
    }

    fun uploadFile(id: String, content: ByteArray) {
        uploadFile(id, content, DEFAULT_UPLOAD_CONTENT_TYPE)
    }

    fun uploadFile(id: String, content: ByteArray, contentType: String) {
        val request = Request.Builder()
            .url(url("file", id))
            .put(content.toBinaryRequestBody(contentType))
            .authorized()
            .build()

        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }

    fun downloadFile(id: String): ByteArray {
        return downloadFileInternal("file", id)
    }

    fun deleteFile(id: String) {
        val request = Request.Builder()
            .url(url("file", id))
            .delete()
            .authorized()
            .build()

        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }

    fun createPublicUrl(id: String, durationMinutes: Long): String {
        return createPublicUrlInternal(durationMinutes, "file", id, "public-url")
    }

    fun uploadTemporaryFile(content: ByteArray): TemporaryFileUploadResponse {
        return uploadTemporaryFile(content, DEFAULT_UPLOAD_CONTENT_TYPE)
    }

    fun uploadTemporaryFile(content: ByteArray, contentType: String): TemporaryFileUploadResponse {
        val request = Request.Builder()
            .url(url("temp-file"))
            .post(content.toBinaryRequestBody(contentType))
            .authorized()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw mapError(response.code, response.body?.string())
            }

            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isBlank()) {
                throw FileserverClientException(response.code, "Missing response body")
            }

            return json.decodeFromString(TemporaryFileUploadResponse.serializer(), responseBody)
        }
    }

    fun deleteTemporaryFile(tempFileId: String) {
        val request = Request.Builder()
            .url(url("temp-file", tempFileId))
            .delete()
            .authorized()
            .build()

        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }

    fun downloadTemporaryFile(tempFileId: String): ByteArray {
        return downloadFileInternal("temp-file", tempFileId)
    }

    fun promoteTemporaryFile(tempFileId: String, fileId: String) {
        val request = Request.Builder()
            .url(url("temp-file", tempFileId, "promote", fileId))
            .post(ByteArray(0).toRequestBody(null))
            .authorized()
            .build()

        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }

    fun createPublicUrlForTemporaryFile(tempFileId: String, durationMinutes: Long): String {
        return createPublicUrlInternal(durationMinutes, "temp-file", tempFileId, "public-url")
    }

    private fun downloadFileInternal(vararg pathSegments: String): ByteArray {
        val request = Request.Builder()
            .url(url(*pathSegments))
            .get()
            .authorized()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return response.body?.bytes() ?: ByteArray(0)
            }

            throw mapError(response.code, response.body?.string())
        }
    }

    private fun createPublicUrlInternal(durationMinutes: Long, vararg pathSegments: String): String {
        val payload = json.encodeToString(CreatePublicUrlRequest(durationMinutes))
        val request = Request.Builder()
            .url(url(*pathSegments))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .authorized()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw mapError(response.code, response.body?.string())
            }

            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isBlank()) {
                throw FileserverClientException(response.code, "Missing response body")
            }

            return json.decodeFromString(CreatePublicUrlResponse.serializer(), responseBody).publicUrl
        }
    }

    private fun Request.Builder.authorized(): Request.Builder {
        return header("Authorization", "Bearer $bearerToken")
    }

    private fun ByteArray.toBinaryRequestBody(contentType: String) =
        toRequestBody(
            requireNotNull(getMediaTypeInternal(contentType)) {
                "Invalid contentType: '$contentType'"
            }
        )



    private fun url(vararg segments: String): HttpUrl {
        val builder = baseHttpUrl.newBuilder()
        for (segment in segments) {
            builder.addPathSegment(segment)
        }
        return builder.build()
    }

    private fun ensureSuccess(response: okhttp3.Response) {
        if (response.isSuccessful) {
            return
        }
        throw mapError(response.code, response.body?.string())
    }

    private fun mapError(statusCode: Int, rawBody: String?): FileserverClientException {
        val parsed = parseError(rawBody)
        val message = parsed?.message ?: "Private API request failed with status $statusCode"
        val errorCode = parsed?.error

        return when (statusCode) {
            400 -> BadRequestException(statusCode, message, errorCode)
            401 -> UnauthorizedException(statusCode, message, errorCode)
            404 -> NotFoundException(statusCode, message, errorCode)
            413 -> PayloadTooLargeException(statusCode, message, errorCode)
            in 500..599 -> ServerException(statusCode, message, errorCode)
            else -> FileserverClientException(statusCode, message, errorCode)
        }
    }

    private fun parseError(rawBody: String?): ErrorResponse? {
        if (rawBody.isNullOrBlank()) {
            return null
        }

        return runCatching {
            json.decodeFromString(ErrorResponse.serializer(), rawBody)
        }.getOrNull()
    }
}

@Serializable
private data class ErrorResponse(val error: String? = null, val message: String? = null)

@Serializable
private data class HealthResponse(val status: String)
