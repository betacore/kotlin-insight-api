package com.coop.technologies.kotlinInsightApi

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormPart
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.append
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.contentType
import kotlinx.io.core.buildPacket
import kotlinx.io.core.writeFully
import kotlinx.io.streams.asInput
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

object InsightCloudApi {

    private val BASE_URL = "https://insight-api.riada.io"
    private var schemaId: Int = -1
    private var authToken: String = ""
    val mapping: MutableMap<Class<out InsightEntity>, String> = mutableMapOf()
    var objectSchemas: List<ObjectTypeSchema> = emptyList()

    // One Time Initialization
    fun init(schemaId: Int, authToken: String) {
        this.schemaId = schemaId
        this.authToken = authToken
    }

    fun registerClass(clazz: Class<out InsightEntity>, objectName: String) {
        this.mapping[clazz] = objectName
    }

    suspend fun reloadSchema() {
        val schemas = httpClient().get<List<ObjectTypeSchema>> {
            url("$BASE_URL/rest/insight/1.0/objectschema/1/objecttypes/flat")
            header("Authorization", "Bearer $authToken")
        }
        val fullSchemas = schemas.map {
            val attributes = httpClient().get<List<ObjectTypeSchemaAttribute>> {
                url("$BASE_URL/rest/insight/1.0/objecttype/${it.id}/attributes")
                header("Authorization", "Bearer $authToken")
            }
            it.attributes = attributes
            it
        }
        objectSchemas = fullSchemas
    }

    suspend fun <T : InsightEntity> getObjectsRaw(clazz: Class<T>): List<InsightObject> {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient().get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=$objectName&includeTypeAttributes=true")
            header("Authorization", "Bearer $authToken")
        }.objectEntries
    }

    suspend fun <T : InsightEntity> getObjectRaw(clazz: Class<T>, id: Int): InsightObject? {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient().get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=$objectName and objectId=$id&includeTypeAttributes=true")
            header("Authorization", "Bearer $authToken")
        }.objectEntries.firstOrNull()
    }

    suspend fun <T : InsightEntity> getObjectRawByName(clazz: Class<T>, name: String): InsightObject? {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient().get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=$objectName and Name=\"$name\"&includeTypeAttributes=true")
            header("Authorization", "Bearer $authToken")
        }.objectEntries.firstOrNull()
    }

    suspend fun <T: InsightEntity> getObjectsRawByIQL(clazz: Class<T>, iql: String): List<InsightObject> {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient().get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=$objectName and $iql&includeTypeAttributes=true")
            header("Authorization", "Bearer $authToken")
        }.objectEntries
    }

    suspend fun <T : InsightEntity> getObjects(clazz: Class<T>): List<T> {
        val objects = getObjectsRaw(clazz)
        return objects.map {
            parseInsightObjectToClass(clazz, it)
        }
    }

    suspend fun <T : InsightEntity> getObject(clazz: Class<T>, id: Int): T? {
        val obj = getObjectRaw(clazz, id)
        return obj?.let { parseInsightObjectToClass(clazz, it) }
    }

    suspend fun <T : InsightEntity> getObjectByName(clazz: Class<T>, name: String): T? {
        val obj = getObjectRawByName(clazz, name)
        return obj?.let { parseInsightObjectToClass(clazz, it) }
    }

    suspend fun <T : InsightEntity> getObjectByIQL(clazz: Class<T>, iql: String): List<T> {
        val objs = getObjectsRawByIQL(clazz, iql)
        return objs.map { parseInsightObjectToClass(clazz, it) }
    }

    private suspend fun resolveInsightReference(objType: String, id: Int): InsightObject? {
        val objects = httpClient().get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=$objType and objectId=$id&includeTypeAttributes=true")
            header("Authorization", "Bearer $authToken")
        }
        return objects.objectEntries.firstOrNull()
    }

    suspend fun <T : InsightEntity> parseInsightObjectToClass(clazz: Class<T>, obj: InsightObject): T {
        val fieldsMap = clazz.declaredFields.map {
            it.name.capitalize() to it.type
        }.toMap()
        val id = listOf("Id" to obj.id).toMap()
        val values = obj.attributes.filter { it.objectTypeAttribute?.referenceObjectType == null }.map {
            it.objectTypeAttribute?.name to it.objectAttributeValues.first().value
        }.toMap()
        val references = obj.attributes.filter { it.objectTypeAttribute?.referenceObjectType != null }.map {
            it.objectTypeAttribute?.referenceObjectType?.name to
                    (fieldsMap.get(
                        it.objectTypeAttribute?.referenceObjectType?.name ?: ""
                    )?.let { Class.forName(it.name) }?.let { it1 ->
                        InsightReference(
                            objectType = it.objectTypeAttribute?.referenceObjectType?.name ?: "",
                            objectId = it.objectTypeAttribute?.referenceObjectTypeId ?: 0,
                            clazzToParse = it1 as Class<T>
                        )
                    })
        }.toMap()
        val allValues = id + values
        return parseObject(clazz, fieldsMap, allValues, references)
    }

    private suspend fun <T : InsightEntity> parseObject(
        clazz: Class<T>,
        fields: Map<String, Class<out Any?>>,
        values: Map<String?, Any?>,
        references: Map<String?, InsightReference?>
    ): T {
        val kobj = Class.forName(clazz.name).kotlin
        val result = kobj.primaryConstructor
            ?.parameters
            ?.fold(emptyMap<KParameter, Any?>()) { map, parameter ->
                val value = values.get(parameter.name?.capitalize())
                val reference = references.get(parameter.name?.capitalize())
                val definedClass = fields.get(parameter.name?.capitalize())
                var result = value
                if (value == null && reference != null) {
                    val referenceObject = references.get(parameter.name?.capitalize())
                    val insightObject =
                        resolveInsightReference(referenceObject?.objectType ?: "", referenceObject?.objectId ?: 0)
                    if (InsightEntity::class.java == Class.forName(reference.clazzToParse.name).superclass) {
                        val parsedObject = insightObject?.let {
                            parseInsightObjectToClass(
                                referenceObject?.clazzToParse as Class<T>,
                                it
                            )
                        }
                        result = parsedObject
                    } else if (reference.clazzToParse == String::class.java) {
                        result = insightObject?.attributes?.filter { it.objectTypeAttribute?.name == "Name" }?.first()
                            ?.objectAttributeValues?.first()?.value
                    } else if (reference.clazzToParse == Int::class.java) {
                        result = insightObject?.id
                    }
                }
                map + (parameter to result)
            }?.let {
                kobj.primaryConstructor?.callBy(it) as T
            }?.apply {
                this.id = values.get("Id") as Int
                this.key = values.get("Key") as String
            } ?: throw RuntimeException("Object ${clazz.name} could not be loaded")
        return result
    }


    suspend fun <T : InsightEntity> createObject(obj: T): T {
        reloadSchema()
        val schema = objectSchemas.filter { it.name == mapping.get(obj::class.java) }.first()
        val resolvedObj = resolveReferences(obj)

        val editItem = parseObjectToObjectTypeAttributes(resolvedObj, schema)
        val json = httpClient().post<String> {
            url("$BASE_URL/rest/insight/1.0/object/create")
            header("Authorization", "Bearer $authToken")
            contentType(ContentType.Application.Json)
            body = editItem
        }
        val jsonObject = JsonParser().parse(json).asJsonObject
        obj.id = jsonObject.get("id").asInt
        obj.key = jsonObject.get("objectKey").asString
        return obj
    }

    private suspend fun <T : InsightEntity> resolveReferences(obj: T): T {
        obj::class.memberProperties.map {
            it as KProperty1<Any, *>
        }.filter {
            val newObj = it.get(obj)
            Class.forName(newObj?.javaClass?.name).superclass == InsightEntity::class.java
        }.onEach {
            val item = it.get(obj) as T
            //getObjectRaw(it.second::class.java)
            if (item.id == -1 || item.key.isBlank()) {
                // get entity by name, if not exists create
                val resolvedObject = getObjectByName(item.javaClass, it.name) ?: createObject(item)
                item.id = resolvedObject.id
                item.key = resolvedObject.key
            }
        }
        return obj
    }

    private fun <T : InsightEntity> parseObjectToObjectTypeAttributes(
        obj: T,
        schema: ObjectTypeSchema
    ): ObjectEditItem {
        val attributes: List<ObjectEditItemAttribute> = obj::class.java.declaredFields.map {
            val name = it.name
            var value =
                (obj::class.memberProperties.filter { it.name == name }.firstOrNull() as KProperty1<Any, *>?)?.get(obj)
            if (it.type.superclass == InsightEntity::class.java) {
                // override with references --> key
                value = (value as InsightEntity).key
            }
            schema.attributes.filter { it.name == name.capitalize() }.firstOrNull()?.let {
                ObjectEditItemAttribute(it.id, listOf(ObjectEditItemAttributeValue(value)))
            }
        }.filterNotNull()
        return ObjectEditItem(schema.id, attributes)
    }

    suspend fun deleteObject(id: Int): Boolean {
        val json = httpClient().delete<String> {
            url("$BASE_URL/rest/insight/1.0/object/$id")
            header("Authorization", "Bearer $authToken")
            contentType(ContentType.Application.Json)
        }
        return true
    }

    suspend fun <T: InsightEntity> updateObject(obj: T): T {
        reloadSchema()
        val schema = objectSchemas.filter { it.name == mapping.get(obj::class.java) }.first()
        val resolvedObj = resolveReferences(obj)

        val editItem = parseObjectToObjectTypeAttributes(resolvedObj, schema)
        val json = httpClient().put<String> {
            url("$BASE_URL/rest/insight/1.0/object/${obj.id}")
            header("Authorization", "Bearer $authToken")
            contentType(ContentType.Application.Json)
            body = editItem
        }
        val jsonObject = JsonParser().parse(json).asJsonObject
        obj.id = jsonObject.get("id").asInt
        obj.key = jsonObject.get("objectKey").asString
        return obj
    }

    suspend fun <T: InsightEntity> getHistory(obj: T): List<InsightHistoryItem> {
        return httpClient().get<List<InsightHistoryItem>> {
            url("$BASE_URL/rest/insight/1.0/object/${obj.id}/history")
            header("Authorization", "Bearer $authToken")
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun <T: InsightEntity> getAttachments(obj: T): List<InsightAttachment> {
        return httpClient().get<List<InsightAttachment>> {
            url("$BASE_URL/rest/insight/1.0/attachments/object/${obj.id}")
            header("Authorization", "Bearer $authToken")
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun downloadAttachment(obj: InsightAttachment): ByteArray {
        val url = obj.url
        val result =  httpClient().get<ByteArray> {
            url("$BASE_URL$url")
            header("Authorization", "Bearer ${authToken}")
        }
        return result
    }

    suspend fun <T: InsightEntity> uploadAttachment(obj: T, filename: String, byteArray: ByteArray, comment: String = ""): List<InsightAttachment> {
        val mimeType = URLConnection.guessContentTypeFromName(filename)
        val result =  httpClient().post<List<InsightAttachment>> {
            url("$BASE_URL/rest/insight/1.0/attachments/object/${obj.id}")
            header("Authorization", "Bearer ${authToken}")
            header("Connection", "keep-alive")
            header("Cache-Control", "no-cache")
            body = MultiPartFormDataContent(
                formData {
                    this.append(
                        "file",
                        byteArray,
                        Headers.build {
                            append(HttpHeaders.ContentType, mimeType)
                            append(HttpHeaders.ContentDisposition,"filename=$filename")
                        })
                    this.append(FormPart("encodedComment", comment))
                }
            )
        }
        return result
    }

    suspend fun deleteAttachment(attachment: InsightAttachment): String {
        val result =  httpClient().delete<String> {
            url("$BASE_URL/rest/insight/1.0/attachments/${attachment.id}")
            header("Authorization", "Bearer ${authToken}")
        }
        return result
    }


}