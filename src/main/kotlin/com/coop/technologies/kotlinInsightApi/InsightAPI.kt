package com.coop.technologies.kotlinInsightApi

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormPart
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.net.URLConnection
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

object InsightCloudApi {

    private var BASE_URL = "https://insight-api.riada.io"
    private var schemaId: Int = -1
    val mapping: MutableMap<Class<out InsightEntity>, String> = mutableMapOf()
    var objectSchemas: List<ObjectTypeSchema> = emptyList()
    var httpClient: HttpClient = httpClient("", "")

    // One Time Initialization
    fun init(schemaId: Int, url: String, username: String, password: String) {
        this.BASE_URL = url
        this.schemaId = schemaId
        this.httpClient = httpClient(username, password)
        runBlocking {
            reloadSchema()
        }
    }

    fun registerClass(clazz: Class<out InsightEntity>, objectName: String) {
        this.mapping[clazz] = objectName
    }

    suspend fun reloadSchema() {
        val schemas = httpClient.get<List<ObjectTypeSchema>> {
            url("$BASE_URL/rest/insight/1.0/objectschema/${schemaId}/objecttypes/flat")
        }
        val fullSchemas = schemas.map {
            val attributes = httpClient.get<List<ObjectTypeSchemaAttribute>> {
                url("$BASE_URL/rest/insight/1.0/objecttype/${it.id}/attributes")
            }
            it.attributes = attributes
            it
        }
        objectSchemas = fullSchemas
    }

    suspend fun <T : InsightEntity> getObjectsRaw(clazz: Class<T>): List<InsightObject> {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient.get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=\"$objectName\"&includeTypeAttributes=true")
        }.objectEntries
    }

    suspend fun <T : InsightEntity> getObjectRaw(clazz: Class<T>, id: Int): InsightObject? {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient.get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=\"$objectName\" and objectId=$id&includeTypeAttributes=true")
        }.objectEntries.firstOrNull()
    }

    suspend fun <T : InsightEntity> getObjectRawByName(
        clazz: Class<T>,
        name: String
    ): InsightObject? {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient.get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=\"$objectName\" and Name=\"$name\"&includeTypeAttributes=true")
        }.objectEntries.firstOrNull()
    }

    suspend fun <T : InsightEntity> getObjectsRawByIQL(
        clazz: Class<T>,
        iql: String
    ): List<InsightObject> {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient.get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=\"$objectName\" and $iql&includeTypeAttributes=true")
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

    private suspend fun resolveInsightReference(objectType: String, id: Int): InsightObject? {
        val objects = httpClient.get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=\"$objectType\" and objectId=$id&includeTypeAttributes=true")
        }
        return objects.objectEntries.firstOrNull()
    }

    suspend fun <T : InsightEntity> parseInsightObjectToClass(
        clazz: Class<T>,
        obj: InsightObject
    ): T {
        val fieldsMap = clazz.declaredFields.map {
            it.name.capitalize() to it.type
        }.toMap()
        val id = listOf("Id" to obj.id).toMap()
        val values =
            obj.attributes.filter { it.objectTypeAttribute?.referenceObjectType == null }.map {
                it.objectTypeAttribute?.name to
                        if (it.objectAttributeValues.size == 1) it.objectAttributeValues.first().value
                        else it.objectAttributeValues.map { it.value }
            }.toMap()
        val references =
            obj.attributes.filter { it.objectTypeAttribute?.referenceObjectType != null }.map {
                it.objectTypeAttribute?.name to
                        (fieldsMap.get(
                            it.objectTypeAttribute?.name ?: ""
                        )?.let { Class.forName(it.name) }?.let { it1 ->
                            InsightReference(
                                objectType = it.objectTypeAttribute?.referenceObjectType?.name
                                    ?: "",
                                //objectId = it.objectTypeAttribute?.referenceObjectTypeId ?: 0,
                                objectIds = it.objectAttributeValues.map { it.referencedObject.id },
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
            ?.map { parameter ->
                var value = values[parameter.name?.capitalize()]
                val reference = references[parameter.name?.capitalize()]
                val definedClass = fields[parameter.name?.capitalize()]
                val result = when {
                    definedClass == Int::class.java -> value.toString().toInt()
                    definedClass == Float::class.java -> value.toString().toFloat()
                    definedClass == Double::class.java -> value.toString().toDouble()
                    definedClass == Boolean::class.java -> value.toString().toBoolean()
                    definedClass == String::class.java -> value.toString()
                    definedClass == List::class.java && reference == null -> {
                        val outClass =
                            Class.forName(parameter.type.arguments.first().type!!.javaType.typeName!!)
                        if (value == null) {
                            value = emptyList<String>()
                        }
                        when (outClass) {
                            Integer::class.java -> (value as List<String>).map { it.toInt() }
                            Float::class.java -> (value as List<String>).map { it.toFloat() }
                            Double::class.java -> (value as List<String>).map { it.toDouble() }
                            Boolean::class.java -> (value as List<String>).map { it.toBoolean() }
                            else -> TODO("Unknown outClass for List")
                        }
                    }
                    value == null && reference != null -> {
                        val referenceObject = references[parameter.name?.capitalize()]
                        val insightObjects = referenceObject?.objectIds?.map {
                            resolveInsightReference(referenceObject.objectType, it)
                        }
                        // multi reference
                        if (reference.clazzToParse == List::class.java) {
                            val referenceType =
                                parameter.type.arguments.firstOrNull()?.type?.javaType?.typeName?.let { Class.forName(it) }
                            // object reference
                            when {
                                InsightEntity::class.java == referenceType?.superclass -> {
                                    val clazz =
                                        Class.forName(parameter.type.arguments.first().type!!.javaType.typeName!!)
                                    insightObjects?.map {
                                        parseInsightObjectToClass(
                                            clazz as Class<T>,
                                            it!!
                                        )
                                    } ?: emptyList<T>()
                                }
                                Class.forName("java.lang.Integer") == referenceType -> {
                                    reference.objectIds
                                }
                                String::class.java == referenceType -> {
                                    insightObjects?.mapNotNull {
                                        it?.label
                                    } ?: emptyList<String>()
                                }
                                else -> TODO("Reference-type unhandled")
                            }
                        } else {
                            // single reference
                            when {
                                InsightEntity::class.java == Class.forName(reference.clazzToParse.name).superclass -> {
                                    val parsedObject = insightObjects?.firstOrNull()?.let {
                                        parseInsightObjectToClass(
                                            referenceObject.clazzToParse as Class<T>,
                                            it
                                        )
                                    }
                                    parsedObject
                                }
                                reference.clazzToParse == String::class.java -> {
                                    insightObjects?.firstOrNull()?.attributes?.first { it.objectTypeAttribute?.name == "Name" }
                                        ?.objectAttributeValues?.first()?.value
                                }
                                reference.clazzToParse == Int::class.java -> {
                                    insightObjects?.firstOrNull()?.id
                                }
                                else -> TODO("Single Ref unhandled")
                            }
                        }
                    }
                    else -> {
                        throw NotImplementedError("cls: ${definedClass} - value: ${value}")
                        null
                    }
                }
                (parameter to result)
            }?.toMap()
            ?.let {
                kobj.primaryConstructor?.callBy(it) as T
            }?.apply {
                this.id = values["Id"] as Int
                this.key = values["Key"] as String
            } ?: throw RuntimeException("Object ${clazz.name} could not be loaded")
        return result
    }


    suspend fun <T : InsightEntity> createObject(obj: T): T {
        reloadSchema()
        val schema = objectSchemas.first { it.name == mapping[obj::class.java] }
        val resolvedObj = resolveReferences(obj)

        val editItem = parseObjectToObjectTypeAttributes(resolvedObj, schema)
        val json = httpClient.post<String> {
            url("$BASE_URL/rest/insight/1.0/object/create")
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
            Class.forName(newObj!!.javaClass.name).superclass == InsightEntity::class.java
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
                (obj::class.memberProperties.filter { it.name == name }
                    .firstOrNull() as KProperty1<Any, *>?)?.get(obj)
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
        val json = httpClient.delete<String> {
            url("$BASE_URL/rest/insight/1.0/object/$id")
            contentType(ContentType.Application.Json)
        }
        return true
    }

    suspend fun <T : InsightEntity> updateObject(obj: T): T {
        reloadSchema()
        val schema = objectSchemas.filter { it.name == mapping.get(obj::class.java) }.first()
        val resolvedObj = resolveReferences(obj)

        val editItem = parseObjectToObjectTypeAttributes(resolvedObj, schema)
        val json = httpClient.put<String> {
            url("$BASE_URL/rest/insight/1.0/object/${obj.id}")
            contentType(ContentType.Application.Json)
            body = editItem
        }
        val jsonObject = JsonParser().parse(json).asJsonObject
        obj.id = jsonObject.get("id").asInt
        obj.key = jsonObject.get("objectKey").asString
        return obj
    }

    suspend fun <T : InsightEntity> getHistory(obj: T): List<InsightHistoryItem> {
        return httpClient.get<List<InsightHistoryItem>> {
            url("$BASE_URL/rest/insight/1.0/object/${obj.id}/history")
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun <T : InsightEntity> getAttachments(obj: T): List<InsightAttachment> {
        return httpClient.get {
            url("$BASE_URL/rest/insight/1.0/attachments/object/${obj.id}")
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun downloadAttachment(obj: InsightAttachment): ByteArray {
        val url = obj.url
        val result = httpClient.get<ByteArray> {
            url(url)
        }
        return result
    }

    suspend fun <T : InsightEntity> uploadAttachment(
        obj: T,
        filename: String,
        byteArray: ByteArray,
        comment: String = ""
    ): List<InsightAttachment> {
        val mimeType = URLConnection.guessContentTypeFromName(filename)
        val result = httpClient.post<String> {
            url("$BASE_URL/rest/insight/1.0/attachments/object/${obj.id}")
            header("Connection", "keep-alive")
            header("Cache-Control", "no-cache")
            body = MultiPartFormDataContent(
                formData {
                    this.append(
                        "file",
                        byteArray,
                        Headers.build {
                            append(HttpHeaders.ContentType, mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=$filename")
                        })
                    this.append(FormPart("encodedComment", comment))
                }
            )
        }
        return getAttachments(obj)
    }

    suspend fun deleteAttachment(attachment: InsightAttachment): String {
        val result = httpClient.delete<String> {
            url("$BASE_URL/rest/insight/1.0/attachments/${attachment.id}")
        }
        return result
    }


}
