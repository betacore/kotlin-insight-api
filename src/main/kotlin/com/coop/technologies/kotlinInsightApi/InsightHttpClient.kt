package com.coop.technologies.kotlinInsightApi

import com.alibaba.fastjson.JSON
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.auth.*
import io.ktor.client.features.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*


@KtorExperimentalAPI
internal fun httpClient(user: String, pass: String) =
    HttpClient(CIO) {
        install(Auth) {
            basic {
                username = user
                password = pass
                sendWithoutRequest = true
            }
        }
        expectSuccess = false
        engine {

        }
    }


internal val basePath = listOf("rest", "insight", "1.0")
internal val createComment = Endpoint(basePath + listOf("comment", "create"))
internal val createObject = Endpoint(basePath + listOf("object", "create"))

internal fun objectSchema(schemaId: Int): Endpoint =
    Endpoint(
        basePath + listOf("objectschema", "$schemaId", "objecttypes", "flat")
    )

internal fun objectType(schemaId: Int): Endpoint =
    Endpoint(
        basePath + listOf("objecttype", "$schemaId", "attributes")
    )

internal fun objectsByIql(iql: String, schemaId: Int, pageSize: Int): Endpoint =
    Endpoint(
        basePath + listOf("iql", "objects"),
        mapOf(
            "iql" to iql,
            "objectSchemaId" to "$schemaId",
            "resultPerPage" to "$pageSize",
            "includeTypeAttributes" to "true"
        )
    )

internal fun objectsByIql(iql: String, schemaId: Int, pageSize: Int, page: Int): Endpoint =
    Endpoint(
        basePath + listOf("iql", "objects"),
        mapOf(
            "iql" to iql,
            "objectSchemaId" to "$schemaId",
            "resultPerPage" to "$pageSize",
            "includeTypeAttributes" to "true",
            "page" to "$page"
        )
    )

internal fun objectById(id: Int): Endpoint =
    Endpoint(
        basePath + listOf("object", "$id")
    )

internal fun objectHistoryById(id: Int): Endpoint =
    Endpoint(
        basePath + listOf("object", "history", "$id")
    )

internal fun attachmentByObjectId(objectId: Int): Endpoint =
    Endpoint(
        basePath + listOf("attachments", "object", "$objectId")
    )

internal fun attachmentById(attachmentId: Int): Endpoint =
    Endpoint(
        basePath + listOf("attachments", "$attachmentId")
    )

internal suspend fun handleResult(result: HttpResponse): Either<HttpError, String> =
    when (result.status.value) { // TODO Improve error handling
        in 200..299 -> result.readText().right()
        in 400..499 -> HttpError.BadRequest.left()
        in 500..599 -> HttpError.ServerError.left()
        else -> HttpError.ServerError.left()
    }

internal suspend fun Endpoint.httpGet(
    baseUrl: String,
    httpClient: HttpClient,
    httpHeaders: Map<String, String> = emptyMap()
): Either<HttpError, String> {
    val result = httpClient.get<HttpResponse>(this.toUrl(baseUrl)) {
        headers { httpHeaders.onEach { this.append(it.key, it.value) } }
        contentType(ContentType.Application.Json)
    }
    return handleResult(result)
}

internal suspend fun Endpoint.httpPost(
    requestBody: Any,
    baseUrl: String,
    httpClient: HttpClient,
    httpHeaders: Map<String, String> = emptyMap()
): Either<HttpError, String> {
    val result = httpClient.post<HttpResponse>(this.toUrl(baseUrl)) {
        headers { httpHeaders.onEach { this.append(it.key, it.value) } }
        contentType(ContentType.Application.Json)
        body = JSON.toJSONString(requestBody)
    }
    return handleResult(result)
}

internal suspend fun Endpoint.httpPut(
    requestBody: Any,
    baseUrl: String,
    httpClient: HttpClient,
    httpHeaders: Map<String, String> = emptyMap()
): Either<HttpError, String> {
    val result = httpClient.put<HttpResponse>(this.toUrl(baseUrl)) {
        headers { httpHeaders.onEach { this.append(it.key, it.value) } }
        contentType(ContentType.Application.Json)
        body = JSON.toJSONString(requestBody)
    }
    return handleResult(result)
}

internal suspend fun Endpoint.httpDelete(
    baseUrl: String,
    httpClient: HttpClient,
    httpHeaders: Map<String, String> = emptyMap()
): Either<HttpError, String> {
    val result = httpClient.delete<HttpResponse>(this.toUrl(baseUrl)) {
        headers { httpHeaders.onEach { this.append(it.key, it.value) } }
        contentType(ContentType.Application.Json)
    }
    return handleResult(result)
}

