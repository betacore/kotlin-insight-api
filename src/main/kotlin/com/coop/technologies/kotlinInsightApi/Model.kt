package com.coop.technologies.kotlinInsightApi

import io.ktor.client.*
import java.util.Collections.emptyList
import kotlin.reflect.KClass

sealed class HttpError {
    object BadRequest : HttpError()
    object ServerError : HttpError()
}

data class ExecutionEnvironment(
    val baseUrl: String,
    val schemaId: Int,
    val mapping: Map<KClass<out InsightEntity>, String>,
    val pageSize: Int,
    val ignoreSubtypes: Boolean,
    val objectSchemas: List<ObjectTypeSchema>,
    val httpClient: HttpClient
)

data class Endpoint(
    val path: List<String>,
    val queryParams: Map<String, String> = emptyMap()
)

fun Endpoint.toUrl(baseUrl: String): String =
    "$baseUrl/${path.joinToString("/")}?${queryParams.map { (k, v) -> "$k=$v" }.joinToString("&")}"

open class InsightEntity(
    var id: Int = -1,
    var key: String = "",
    open val name: String = ""
) {
    suspend fun save(environment: ExecutionEnvironment) {
        if (id == -1) {
            environment.createObject(this)
        } else {
            environment.updateObject(this)
        }
    }

    suspend fun delete(environment: ExecutionEnvironment): Boolean {
        if (id == -1) {
            return false
        }
        environment.deleteObject(id)
        return true
    }

    suspend fun getHistory(environment: ExecutionEnvironment): List<InsightHistoryItem> {
        if (id == -1) {
            return emptyList()
        }
        return environment.getHistory(this)
    }

    suspend fun getAttachments(environment: ExecutionEnvironment): List<InsightAttachment> {
        if (id == -1) {
            return emptyList()
        }
        return environment.getAttachments(this)
    }

    suspend fun comment(message: String, environment: ExecutionEnvironment) {
        if (id != -1) {
            environment.createComment(this.id, message)
        }
    }

    suspend fun addAttachment(filename: String, byteArray: ByteArray, comment: String = "", environment: ExecutionEnvironment): InsightAttachment {
        return environment.uploadAttachment(this, filename, byteArray, comment).first()
    }
}


object InsightFactory {
    suspend inline fun <reified T : InsightEntity> findAll(environment: ExecutionEnvironment): List<T> {
        return environment.getObjects(T::class)
    }

    suspend inline fun <reified T : InsightEntity> findById(id: Int, environment: ExecutionEnvironment): T? {
        return environment.getObject(T::class, id)
    }

    suspend inline fun <reified T : InsightEntity> findByName(name: String, environment: ExecutionEnvironment): T? {
        return environment.getObjectByName(T::class, name)
    }

    suspend inline fun <reified T : InsightEntity> findByIQL(iql: String, environment: ExecutionEnvironment): List<T> {
        return environment.getObjectByIQL(T::class, iql)
    }
}

data class ObjectTypeSchema(
    val id: Int,
    val name: String,
    var attributes: List<ObjectTypeSchemaAttribute>?
)

data class ObjectEditItem(
    val objectTypeId: Int,
    val attributes: List<ObjectEditItemAttribute>
)

data class ObjectEditItemAttribute(
    val objectTypeAttributeId: Int,
    val objectAttributeValues: List<ObjectEditItemAttributeValue>
)

data class ObjectEditItemAttributeValue(
    val value: Any?
)

data class ObjectTypeSchemaAttribute(
    val id: Int,
    val name: String
)

data class InsightObjectEntries(
    val objectEntries: List<InsightObject>,
    val pageSize: Int
)

data class InsightObject(
    val id: Int,
    val label: String,
    val objectKey: String,
    val objectType: ObjectType,
    val attributes: List<InsightAttribute>
)

data class ObjectType(
    val id: Int,
    val name: String,
    val objectSchemaId: Int
)

data class InsightCommentBody(
    val objectId: Int,
    val comment: String,
    val role: Int = 0
)

data class InsightAttribute(
    val id: Int,
    val objectTypeAttribute: ObjectTypeAttribute?,
    val objectTypeAttributeId: Int,
    val objectId: Int,
    val objectAttributeValues: List<ObjectAttributeValue>
)

data class ObjectTypeAttribute(
    val id: Int,
    val name: String,
    val referenceObjectTypeId: Int?,
    val referenceObjectType: ObjectType?
)


data class InsightReference<A : InsightEntity>(
    val objectType: String,
    val objects: List<Pair<Int, String>>,
    val clazzToParse: KClass<A>
)

data class ObjectAttributeValue(
    val value: Any?,
    val displayValue: Any?,
    val referencedObject: ReferencedObject?
)

data class ReferencedObject(
    val id: Int,
    val label: String
)

data class InsightHistoryItem(
    val id: Int,
    val affectedAttribute: String,
    val newValue: String,
    val actor: Actor,
    val type: Int,
    val created: String,
    val updated: String,
    val objectId: Int
)

data class Actor(
    val name: String
)

data class InsightAttachment(
    val id: Int,
    val author: String,
    val mimeType: String,
    val filename: String,
    val filesize: String,
    val created: String,
    val comment: String,
    val commentOutput: String,
    val url: String
) {

    suspend fun getBytes(environment: ExecutionEnvironment): ByteArray {
        return environment.downloadAttachment(this)
    }

    suspend fun delete(environment: ExecutionEnvironment): Boolean {
        if (id <= 0) {
            return false
        }
        environment.deleteAttachment(this)
        return true
    }
}