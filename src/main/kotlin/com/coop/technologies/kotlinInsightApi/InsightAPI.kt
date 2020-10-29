package com.coop.technologies.kotlinInsightApi

import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URLConnection
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
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&resultPerPage=${Int.MAX_VALUE}&iql=objectType=\"$objectName\"&includeTypeAttributes=true")
        }.objectEntries
    }

    suspend fun <T : InsightEntity> getObjectRaw(clazz: Class<T>, id: Int): InsightObject? {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient.get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&resultPerPage=${Int.MAX_VALUE}&iql=objectType=\"$objectName\" and objectId=$id&includeTypeAttributes=true")
        }.objectEntries.firstOrNull()
    }

    suspend fun <T : InsightEntity> getObjectRawByName(
        clazz: Class<T>,
        name: String
    ): InsightObject? {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient.get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&resultPerPage=${Int.MAX_VALUE}&iql=objectType=\"$objectName\" and Name=\"$name\"&includeTypeAttributes=true")
        }.objectEntries.firstOrNull()
    }

    suspend fun <T : InsightEntity> getObjectsRawByIQL(
        clazz: Class<T>,
        iql: String
    ): List<InsightObject> {
        val objectName = mapping.get(clazz) ?: ""
        return httpClient.get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&resultPerPage=${Int.MAX_VALUE}&iql=objectType=\"$objectName\" and $iql&includeTypeAttributes=true")
        }.objectEntries
    }

    suspend fun <T : InsightEntity> getObjects(clazz: Class<T>): List<T> {
        val objects = getObjectsRaw(clazz)
        return parseInsightObjectsToClass(clazz, objects)
    }

    suspend fun <T : InsightEntity> getObject(clazz: Class<T>, id: Int): T? {
        val obj = getObjectRaw(clazz, id)
        return parseInsightObjectsToClass(clazz, listOfNotNull(obj)).firstOrNull()
    }

    suspend fun <T : InsightEntity> getObjectByName(clazz: Class<T>, name: String): T? {
        val obj = getObjectRawByName(clazz, name)
        return parseInsightObjectsToClass(clazz, listOfNotNull(obj)).firstOrNull()
    }

    suspend fun <T : InsightEntity> getObjectByIQL(clazz: Class<T>, iql: String): List<T> {
        val objs = getObjectsRawByIQL(clazz, iql)
        return parseInsightObjectsToClass(clazz, objs)
    }

    private suspend fun resolveInsightReferences(objectType: String, ids: Set<Int>): List<InsightObject> {
        val results = ids.chunked(50).map { idList ->
            httpClient.get<InsightObjectEntries> {
                url(
                    "$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&resultPerPage=${Int.MAX_VALUE}&iql=objectType=\"$objectType\" and objectId in (${
                        idList.joinToString(
                            ","
                        )
                    })&includeTypeAttributes=true"
                )
            }.objectEntries
        }
        return results.flatten()
    }

    suspend fun <T : InsightEntity> parseInsightObjectsToClass(
        clazz: Class<T>,
        objs: List<InsightObject>
    ): List<T> {
        val refs = buildReferenceMap(objs, clazz)
            .mapNotNull { (field, ref) ->
                when (ref?.clazzToParse) {
                    null -> null
                    List::class.java -> {
                        val referenceType =
                            clazz.kotlin
                                .primaryConstructor
                                ?.parameters
                                ?.first { it.name?.capitalize() == field }
                                ?.type
                                ?.arguments?.firstOrNull()?.type?.javaType?.typeName?.let { Class.forName(it) }
                        when {
                            referenceType == InsightSimpleObject::class.java -> {
                                field to ref.objects.map { InsightSimpleObject(it.first, it.second) }
                            }
                            referenceType?.superclass == InsightEntity::class.java ->
                                field to parseInsightObjectsToClass(
                                    referenceType as Class<InsightEntity>,
                                    resolveInsightReferences(ref.objectType, ref.objects.map { it.first }.toSet())
                                )
                            else -> null
                        }
                    }
                    else -> {
                        ref.let {
                            field to parseInsightObjectsToClass(
                                ref.clazzToParse,
                                resolveInsightReferences(ref.objectType, ref.objects.map { it.first }.toSet())
                            )
                        }
                    }
                }
            }.toMap()


        return objs.map { obj ->
            val references = buildReferenceMap(listOf(obj), clazz)
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
            val allValues = id + values
            parseObject(clazz, fieldsMap, allValues, references, refs)
        }
    }

    private fun <T : InsightEntity> buildReferenceMap(
        objs: List<InsightObject>,
        clazz: Class<T>
    ): Map<String?, InsightReference<T>?> {
        return objs.map { obj ->
            val fieldsMap = clazz.declaredFields.map {
                it.name.capitalize() to it.type
            }.toMap()
            obj.attributes
                .filter { it.objectTypeAttribute?.referenceObjectType != null }
                .map {
                    it.objectTypeAttribute?.name to
                            listOfNotNull((fieldsMap.get(
                                it.objectTypeAttribute?.name ?: ""
                            )?.let { Class.forName(it.name) }?.let { it1 ->
                                InsightReference(
                                    objectType = it.objectTypeAttribute?.referenceObjectType?.name
                                        ?: "",
                                    objects = it.objectAttributeValues.map { it.referencedObject.id to it.referencedObject.label },
                                    clazzToParse = it1 as Class<T>
                                )
                            })
                            )
                }
        }.fold(emptyMap()) { acc, pairList ->
            acc + pairList.map { (k, v) ->
                k to (v + acc[k]).filterNotNull()
            }.map { (k, v) ->
                k to if(v.isEmpty()) v.firstOrNull() else v.flatten()
            }
        }
    }

    private fun <A : InsightEntity> List<InsightReference<A>>.flatten(): InsightReference<A> =
        this.fold(this.first().copy(objects = emptyList())) { acc, ref ->
            acc?.copy(objects = acc.objects + ref.objects)
        }

    private suspend fun <T : InsightEntity> parseObject(
        clazz: Class<T>,
        fields: Map<String, Class<out Any?>>,
        values: Map<String?, Any?>,
        references: Map<String?, InsightReference<T>?>,
        referencedObjects: Map<String?, List<InsightEntity>>
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
                    definedClass == String::class.java -> {
                        try {
                            value as String?
                        } catch (e: Exception) {
                            null
                        }
                    }
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
                            String::class.java -> value as List<String>
                            else -> {
                                if (mapping.keys.contains(outClass)) {
                                    (value as List<InsightObject>).flatMap {
                                        parseInsightObjectsToClass(
                                            mapping.keys.first { key -> key == outClass },
                                            listOf(it)
                                        )
                                    }
                                } else TODO("Unknown outClass for List: ${outClass.name}")
                            }
                        }
                    }
                    definedClass != null && value == null && reference == null -> null
                    value == null && reference != null -> {
                        val reference = references[parameter.name?.capitalize()]
                        val referenceObjects = referencedObjects[parameter.name?.capitalize()]
                        val insightObjects = reference?.objects?.map { it.first }
                        val intermediate = insightObjects?.flatMap { reference ->
                            referenceObjects?.filter { it.id == reference }.orEmpty()
                        }
                        if (reference?.clazzToParse == List::class.java) intermediate
                        else intermediate?.firstOrNull()
                    }
                    else -> {
                        throw NotImplementedError("cls: ${definedClass} - value: ${value} - reference: $reference")
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
        log.debug("ParsedObject: [$attributes]")
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
        val schema = objectSchemas.first { it.name == mapping[obj::class.java] }
        val resolvedObj = resolveReferences(obj)

        val editItem = parseObjectToObjectTypeAttributes(resolvedObj, schema)
        val json = httpClient.put<String> {
            url("$BASE_URL/rest/insight/1.0/object/${obj.id}")
            contentType(ContentType.Application.Json)
            body = editItem
        }
        val jsonObject = JsonParser.parseString(json).asJsonObject
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

    private val log = LoggerFactory.getLogger(InsightCloudApi::class.java)
}
