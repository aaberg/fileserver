package net.aabergs.client.privateapi

open class FileserverClientException(
    val statusCode: Int,
    message: String,
    val errorCode: String? = null
) : RuntimeException(message)

class BadRequestException(statusCode: Int, message: String, errorCode: String?) :
    FileserverClientException(statusCode, message, errorCode)

class UnauthorizedException(statusCode: Int, message: String, errorCode: String?) :
    FileserverClientException(statusCode, message, errorCode)

class NotFoundException(statusCode: Int, message: String, errorCode: String?) :
    FileserverClientException(statusCode, message, errorCode)

class PayloadTooLargeException(statusCode: Int, message: String, errorCode: String?) :
    FileserverClientException(statusCode, message, errorCode)

class ServerException(statusCode: Int, message: String, errorCode: String?) :
    FileserverClientException(statusCode, message, errorCode)
