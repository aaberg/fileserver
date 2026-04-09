package net.aabergs.client.privateapi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.aabergs.client.privateapi.dto.CreatePublicUrlRequest
import net.aabergs.client.privateapi.dto.CreatePublicUrlResponse
import net.aabergs.client.privateapi.dto.UpdateFileRequest
import net.aabergs.client.privateapi.dto.UpdateFileResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class FileserverClient(
    baseUrl: String,
    private val bearerToken: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
    }
) {
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
        val request = Request.Builder()
            .url(url("file", id))
            .put(content.toRequestBody("application/octet-stream".toMediaType()))
            .authorized()
            .build()

        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
        }
    }

    fun downloadFile(id: String): ByteArray {
        val request = Request.Builder()
            .url(url("file", id))
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
        val payload = json.encodeToString(CreatePublicUrlRequest(durationMinutes))
        val request = Request.Builder()
            .url(url("file", id, "public-url"))
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

    fun uploadTemporaryFile(id: String, content: ByteArray, ttlSeconds: Long? = null): Int {
        val urlBuilder = url("file", id).newBuilder()
        urlBuilder.addQueryParameter("temporary", "true")
        ttlSeconds?.let { urlBuilder.addQueryParameter("ttl", it.toString()) }
        
        val request = Request.Builder()
            .url(urlBuilder.build())
            .put(content.toRequestBody("application/octet-stream".toMediaType()))
            .authorized()
            .build()

        client.newCall(request).execute().use { response ->
            ensureSuccess(response)
            return response.code
        }
    }

    fun finalizeFile(id: String): UpdateFileResponse {
        val payload = json.encodeToString(UpdateFileRequest(temporary = false))
        val request = Request.Builder()
            .url(url("file", id))
            .patch(payload.toRequestBody("application/json".toMediaType()))
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

            return json.decodeFromString(UpdateFileResponse.serializer(), responseBody)
        }
    }

    private fun Request.Builder.authorized(): Request.Builder {
        return header("Authorization", "Bearer $bearerToken")
    }

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
