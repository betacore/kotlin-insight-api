package com.coop.technologies.kotlinInsightApi

import java.util.Collections.emptyList
import kotlin.reflect.KClass

data class Endpoint(
    val path: List<String>,
    val queryParams: Map<String,String> = emptyMap()
)

fun Endpoint.toUrl(baseUrl: String): String =
    "$baseUrl/${path.joinToString("/")}?${queryParams.map { (k,v) -> "$k=$v" }.joinToString("&")}"

open class InsightEntity(
    var id: Int = -1,
    var key: String = "",
    val name: String = ""
) {
    suspend fun save() {
        if (id == -1) {
            InsightCloudApi.createObject(this)
        } else {
            InsightCloudApi.updateObject(this)
        }
    }

    suspend fun delete(): Boolean {
        if (id == -1) {
            return false
        }
        InsightCloudApi.deleteObject(id)
        return true
    }

    suspend fun getHistory(): List<InsightHistoryItem> {
        if (id == -1) {
            return emptyList()
        }
        return InsightCloudApi.getHistory(this)
    }

    suspend fun getAttachments(): List<InsightAttachment> {
        if (id == -1) {
            return emptyList()
        }
        return InsightCloudApi.getAttachments(this)
    }

    suspend fun comment(message: String) {
        if (id != -1) {
            InsightCloudApi.createComment(this.id, message)
        }
    }

    suspend fun addAttachment(filename: String, byteArray: ByteArray, comment: String = ""): InsightAttachment {
        return InsightCloudApi.uploadAttachment(this, filename, byteArray, comment).first()
    }
}


object InsightFactory {
    suspend inline fun <reified T : InsightEntity> findAll(): List<T> {
        return InsightCloudApi.getObjects(T::class)
    }

    suspend inline fun <reified T : InsightEntity> findById(id: Int): T? {
        return InsightCloudApi.getObject(T::class, id)
    }

    suspend inline fun <reified T : InsightEntity> findByName(name: String): T? {
        return InsightCloudApi.getObjectByName(T::class, name)
    }

    suspend inline fun <reified T : InsightEntity> findByIQL(iql: String): List<T> {
        return InsightCloudApi.getObjectByIQL(T::class, iql)
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

data class InsightErrorResponse(
    val errorMessages: List<String>,
    val errors: Map<String,String>
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

    suspend fun getBytes(): ByteArray {
        return InsightCloudApi.downloadAttachment(this)
    }


    suspend fun delete(): Boolean {
        if (id <= 0) {
            return false
        }
        InsightCloudApi.deleteAttachment(this)
        return true
    }
}