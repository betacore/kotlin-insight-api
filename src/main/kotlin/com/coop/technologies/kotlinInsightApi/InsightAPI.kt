package com.coop.technologies.kotlinInsightApi

import com.alibaba.fastjson.JSON
import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URLConnection
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

object InsightCloudApi {

    private var BASE_URL = "https://insight-api.riada.io"
    private var schemaId: Int = -1

    private val objectUrl: String
        get() = "$BASE_URL/rest/insight/1.0/iql/objects"

    private val objectSchemaUrl: String
        get() = "$BASE_URL/rest/insight/1.0/objectschema"

    private val objectTypeUrl: String
        get() = "$BASE_URL/rest/insight/1.0/objecttype"

    private val mapping: MutableMap<KClass<out InsightEntity>, String> = mutableMapOf()
    private var pageSize: Int = 50
    var objectSchemas: List<ObjectTypeSchema> = emptyList()
    private var ignoreSubtypes: Boolean = false

    @KtorExperimentalAPI
    var httpClient: HttpClient = httpClient("", "")

    // One Time Initialization
    @KtorExperimentalAPI
    fun init(
        schemaId: Int,
        url: String,
        username: String,
        password: String,
        pageSize: Int = 50,
        ignoreSubtypes: Boolean = false
    ) {
        this.pageSize = pageSize
        this.BASE_URL = url
        this.schemaId = schemaId
        this.ignoreSubtypes = ignoreSubtypes
        this.httpClient = httpClient(username, password)
        runBlocking {
            reloadSchema()
        }
    }

    fun registerClass(clazz: KClass<out InsightEntity>, objectName: String) {
        this.mapping[clazz] = objectName
    }

    @KtorExperimentalAPI
    suspend fun reloadSchema() {
        val schemas = JSON.parseArray(httpClient.get<String> {
            url("$objectSchemaUrl/${schemaId}/objecttypes/flat")
        }, ObjectTypeSchema::class.java)
        val fullSchemas = schemas.map {
            val attributes = JSON.parseArray(httpClient.get<String> {
                url("$objectTypeUrl/${it.id}/attributes")
            }, ObjectTypeSchemaAttribute::class.java)
            it.attributes = attributes
            it
        }
        objectSchemas = fullSchemas
    }

    @KtorExperimentalAPI
    private suspend fun <T : InsightEntity> getObjectsRaw(clazz: KClass<T>): List<InsightObject> {
        return getObjectsRawByIQL(clazz, null)
    }

    @KtorExperimentalAPI
    private suspend fun <T : InsightEntity> getObjectRaw(clazz: KClass<T>, id: Int): InsightObject? {
        val iql = "objectId=$id"
        return getObjectsRawByIQL(clazz, iql).firstOrNull()
    }

    @KtorExperimentalAPI
    private suspend fun <T : InsightEntity> getObjectRawByName(clazz: KClass<T>, name: String): InsightObject? {
        val iql = "Name=\"$name\""
        return getObjectsRawByIQL(clazz, iql).firstOrNull()
    }

    @KtorExperimentalAPI
    private suspend fun <T : InsightEntity> getObjectsRawByIQL(
        clazz: KClass<T>,
        iql: String?
    ): List<InsightObject> {
        log.debug("Getting objects for [${clazz.simpleName}] with [$iql]")
        val objectName = mapping[clazz] ?: ""
        val urlFun: HttpRequestBuilder.(Int) -> Unit = { page: Int ->
            if (ignoreSubtypes) {
                url(
                    "$objectUrl?objectSchemaId=$schemaId&resultPerPage=${pageSize}&iql=objectType=\"$objectName\"${
                        iql?.let { " and $it" }.orEmpty()
                    }&includeTypeAttributes=true&page=$page"
                )
            } else {
                url(
                    "$objectUrl?objectSchemaId=$schemaId&resultPerPage=${pageSize}&iql=objectType in objectTypeAndChildren(\"$objectName\")${
                        iql?.let { " and $it" }.orEmpty()
                    }&includeTypeAttributes=true&page=$page"
                )
            }
        }
        val result = JSON.parseObject(httpClient.get<String> {
            urlFun(1)
        }, InsightObjectEntries::class.java)
        val remainingPages = if (result.pageSize > 1) {
            generateSequence(2) { s -> if (s < result.pageSize) s + 1 else null }
        } else emptySequence()
        val pageContents = remainingPages.toList().flatMap { page ->
            JSON.parseObject(httpClient.get<String> {
                urlFun(page)
            }, InsightObjectEntries::class.java).objectEntries
        }

        log.debug("Returning [${(result.objectEntries + pageContents).size}] objects for [${clazz.simpleName}]")
        return result.objectEntries + pageContents
    }

    @KtorExperimentalAPI
    suspend fun <T : InsightEntity> getObjects(clazz: KClass<T>): List<T> {
        val objects = getObjectsRaw(clazz)
        return parseInsightObjectsToClass(clazz, objects)
    }

    @KtorExperimentalAPI
    suspend fun <T : InsightEntity> getObject(clazz: KClass<T>, id: Int): T? {
        val obj = getObjectRaw(clazz, id)
        return parseInsightObjectsToClass(clazz, listOfNotNull(obj)).firstOrNull()
    }

    @KtorExperimentalAPI
    suspend fun <T : InsightEntity> getObjectByName(clazz: KClass<T>, name: String): T? {
        val obj = getObjectRawByName(clazz, name)
        return parseInsightObjectsToClass(clazz, listOfNotNull(obj)).firstOrNull()
    }

    @KtorExperimentalAPI
    suspend fun <T : InsightEntity> getObjectByIQL(clazz: KClass<T>, iql: String): List<T> {
        val objects = getObjectsRawByIQL(clazz, iql)
        return parseInsightObjectsToClass(clazz, objects)
    }

    @KtorExperimentalAPI
    private suspend fun resolveInsightReferences(objectType: String, ids: Set<Int>): List<InsightObject> {
        log.debug("Resolving references for objectType [$objectType]")
        val chunkSize = 50
        val results = ids.chunked(chunkSize).map { idList ->
            JSON.parseObject(httpClient.get<String> {
                if (ignoreSubtypes) {
                    url(
                        "$objectUrl?objectSchemaId=$schemaId&resultPerPage=${chunkSize}&iql=objectType=\"$objectType\" and objectId in (${
                            idList.joinToString(
                                ","
                            )
                        })&includeTypeAttributes=true"
                    )
                } else {
                    url(
                        "$objectUrl?objectSchemaId=$schemaId&resultPerPage=${chunkSize}&iql=objectType in objectTypeAndChildren(\"$objectType\") and objectId in (${
                            idList.joinToString(
                                ","
                            )
                        })&includeTypeAttributes=true"
                    )
                }
            }, InsightObjectEntries::class.java).objectEntries
        }
        log.debug("Resolved references for objectType [$objectType]")
        return results.flatten()
    }

    @KtorExperimentalAPI
    private suspend fun <T : InsightEntity> parseInsightObjectsToClass(
        clazz: KClass<T>,
        objs: List<InsightObject>
    ): List<T> {
        log.debug("Collecting references for objects of type [${clazz.simpleName}]")
        val refs = buildReferenceMap(objs, clazz).resolveReferences(clazz)

        log.debug("Parsing objects of type [${clazz.simpleName}]")
        return objs.map { obj ->
            log.trace("Parsing object [${obj.label}]")
            val references = buildReferenceMap(listOf(obj), clazz)
            val fieldsMap = clazz.declaredMemberProperties.map {
                it.name.capitalize() to it.returnType.jvmErasure
            }.toMap()
            val id = listOf("Id" to obj.id).toMap()
            val values =
                obj.attributes.filter { it.objectTypeAttribute?.referenceObjectType == null }.map { attribute ->
                    attribute.objectTypeAttribute?.name to
                            if (attribute.objectAttributeValues.size == 1) attribute.objectAttributeValues.first().value
                            else attribute.objectAttributeValues.map { it.value }
                }.toMap()
            val allValues = id + values
            parseObject(clazz, fieldsMap, allValues, references, refs)
        }
    }

    @Suppress("UNCHECKED_CAST") // casting to superclass is always possible
    private fun <T : Any, S : Any> KClass<T>.toSuperclass(superclass: KClass<S>): KClass<S> =
        if (this.isSubclassOf(superclass))
            this as KClass<S>
        else throw IllegalStateException("Not subclass of $superclass")

    @KtorExperimentalAPI
    private suspend fun <T : InsightEntity> Map<String?, InsightReference<T>?>.resolveReferences(clazz: KClass<T>) =
        this.mapNotNull { (field, ref) ->
            log.trace("Resolving Reference for field $field")
            when (ref?.clazzToParse) {
                null -> null
                List::class.java -> {
                    val referenceType =
                        clazz.primaryConstructor
                            ?.parameters
                            ?.first { it.name?.capitalize() == field }
                            ?.type
                            ?.arguments?.firstOrNull()?.type?.jvmErasure
                    when {
                        referenceType == InsightSimpleObject::class -> {
                            field to ref.objects.map { InsightSimpleObject(it.first, it.second) }
                        }
                        referenceType?.isSubclassOf(InsightEntity::class) ?: false ->
                            field to parseInsightObjectsToClass(
                                referenceType!!.toSuperclass(InsightEntity::class),
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

    private fun <T : InsightEntity> buildReferenceMap(
        objects: List<InsightObject>,
        clazz: KClass<T>
    ): Map<String?, InsightReference<T>?> {
        log.trace("Building reference map for [${clazz.simpleName}]")
        return objects.map { obj ->
            val fieldsMap = clazz.declaredMemberProperties.map {
                it.name.capitalize() to it.returnType
            }.toMap()
            obj.attributes
                .filter { it.objectTypeAttribute?.referenceObjectType != null }
                .map { attribute ->
                    attribute.objectTypeAttribute?.name to
                            listOfNotNull(
                                (fieldsMap[attribute.objectTypeAttribute?.name ?: ""]?.let { type ->
                                    InsightReference(
                                        objectType = attribute.objectTypeAttribute?.referenceObjectType?.name ?: "",
                                        objects = attribute.objectAttributeValues.map { it.referencedObject!!.id to it.referencedObject.label },
                                        clazzToParse = type.jvmErasure as KClass<T>
                                    )
                                })
                            )
                }
        }.fold(emptyMap()) { acc, pairList ->
            acc + pairList.map { (k, v) ->
                k to (v + acc[k]).filterNotNull()
            }.map { (k, v) ->
                k to if (v.isEmpty()) v.firstOrNull() else v.flatten()
            }
        }
    }

    private fun <A : InsightEntity> List<InsightReference<A>>.flatten(): InsightReference<A> =
        this.fold(this.first().copy(objects = emptyList())) { acc, ref ->
            acc.copy(objects = acc.objects + ref.objects)
        }

    private fun <T : Any> KClass<T>?.isPrimitive(): Boolean =
        when (this) {
            Int::class -> true
            Float::class -> true
            Double::class -> true
            Boolean::class -> true
            String::class -> true

            else -> false
        }

    private fun <T : Any> KClass<T>?.isList(referenceIsNull: Boolean): Boolean =
        when {
            this == List::class.java && referenceIsNull -> true
            else -> false
        }

    private fun Any?.isReference(referenceIsNull: Boolean): Boolean =
        when {
            this == null && !referenceIsNull -> true
            else -> false
        }

    private fun <T : Any> KClass<T>?.transformPrimitive(value: Any?): Any? =
        when (this) {
            Int::class -> value?.toString()?.toInt()
            Float::class -> value?.toString()?.toFloat()
            Double::class -> value?.toString()?.toDouble()
            Boolean::class -> value?.toString()?.toBoolean()
            String::class -> {
                try {
                    value as String?
                } catch (e: Exception) {
                    null
                }
            }
            else -> IllegalStateException("Not a primitive")
        }

    @KtorExperimentalAPI
    private suspend fun <T : InsightEntity> parseObject(
        clazz: KClass<T>,
        fields: Map<String, KClass<out Any>>,
        values: Map<String?, Any?>,
        references: Map<String?, InsightReference<T>?>,
        referencedObjects: Map<String?, List<InsightEntity>>
    ): T {
        val result = clazz.primaryConstructor
            ?.parameters
            ?.map { parameter ->
                var value = values[parameter.name?.capitalize()]
                val reference = references[parameter.name?.capitalize()]
                val definedClass = fields[parameter.name?.capitalize()]
                val result = when {
                    definedClass.isPrimitive() -> definedClass.transformPrimitive(value)

                    definedClass.isList(reference == null) -> {
                        val outClass =
                            parameter.type.arguments.first().type?.jvmErasure
                        if (value == null) {
                            value = emptyList<String>()
                        }
                        when {
                            outClass.isPrimitive() -> (value as List<Any?>).map { outClass.transformPrimitive(it) }
                            else -> {
                                if (mapping.keys.contains(outClass)) {
                                    (value as List<InsightObject>).flatMap {
                                        parseInsightObjectsToClass(
                                            mapping.keys.first { key -> key == outClass },
                                            listOf(it)
                                        )
                                    }
                                } else TODO("Unknown outClass for List: ${outClass?.simpleName}")
                            }
                        }
                    }
                    value.isReference(reference == null) -> {
                        val referenceObjects = referencedObjects[parameter.name?.capitalize()]
                        val insightObjects = reference?.objects?.map { it.first }
                        val intermediate = insightObjects?.flatMap { referenceId ->
                            referenceObjects?.filter { it.id == referenceId }.orEmpty()
                        }
                        if (reference?.clazzToParse == List::class.java) intermediate
                        else intermediate?.firstOrNull()
                    }

                    definedClass != null && value == null && reference == null -> null // null remains null
                    else -> { throw NotImplementedError("cls: $definedClass - value: $value - reference: $reference") }
                }
                (parameter to result)
            }?.toMap()
            ?.let {
                log.trace("Calling primary constructor of ${clazz.simpleName} with parameters $it")
                clazz.primaryConstructor?.callBy(it) as T
            }?.apply {
                this.id = values["Id"] as Int
                this.key = values["Key"] as String
            } ?: throw RuntimeException("Object ${clazz.simpleName} could not be loaded")
        log.trace("Successfully parsed object [${result.key}]")
        return result
    }


    @KtorExperimentalAPI
    suspend fun <T : InsightEntity> createObject(obj: T): T {
        val schema = objectSchemas.first { it.name == mapping[obj::class] }
        val resolvedObj = resolveReferences(obj)

        val editItem = parseObjectToObjectTypeAttributes(resolvedObj, schema)
        val json = httpClient.post<String> {
            url("$BASE_URL/rest/insight/1.0/object/create")
            contentType(ContentType.Application.Json)
            body = editItem
        }
        val jsonObject = JSON.parseObject(json)
        obj.id = jsonObject.getIntValue("id")
        obj.key = jsonObject.getString("objectKey")
        return obj
    }

    @KtorExperimentalAPI
    private suspend fun <T : InsightEntity> resolveReferences(obj: T): T {
        obj::class.memberProperties.map {
            it as KProperty1<Any, *>
        }.filter { property ->
            val newObj = property.get(obj)
            newObj?.javaClass?.kotlin?.isSubclassOf(InsightEntity::class) == true
        }.onEach {
            val item = it.get(obj) as T
            //getObjectRaw(it.second::class.java)
            if (item.id == -1 || item.key.isBlank()) {
                // get entity by name, if not exists create
                val resolvedObject = getObjectByName(item.javaClass.kotlin, it.name) ?: createObject(item)
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
        fun <X> KProperty1<out T, Any?>.value(obj: T): X? =
            (this as KProperty1<T, X>?)?.get(obj)

        val attributes: List<ObjectEditItemAttribute> = obj::class.declaredMemberProperties.mapNotNull { property ->
            val values = when {
                property.returnType.jvmErasure.isSubclassOf(InsightEntity::class) -> {
                    listOf(property.value<InsightEntity>(obj)?.key)
                }
                property.returnType.jvmErasure == List::class -> {
                    property.value<List<*>>(obj)?.mapNotNull { item ->
                        if (item!!::class.isSubclassOf(InsightEntity::class)) {
                            (item as InsightEntity).key
                        } else item
                    }
                }
                else -> listOf(property.value<Any>(obj))
            }

            schema.attributes
                .orEmpty()
                .firstOrNull { it.name == property.name.capitalize() }
                ?.let {
                    ObjectEditItemAttribute(
                        it.id,
                        values.orEmpty().mapNotNull { item -> ObjectEditItemAttributeValue(item) })
                }
        }
        log.debug("ParsedObject: [$attributes]")
        return ObjectEditItem(schema.id, attributes)
    }

    @KtorExperimentalAPI
    suspend fun deleteObject(id: Int): Boolean {
        httpClient.delete<String> {
            url("$BASE_URL/rest/insight/1.0/object/$id")
            contentType(ContentType.Application.Json)
        }
        return true
    }

    @KtorExperimentalAPI
    suspend fun createComment(id: Int, message: String): Boolean {
        httpClient.post<String> {
            url("$BASE_URL/rest/insight/1.0/comment/create")
            contentType(ContentType.Application.Json)
            body = InsightCommentBody(id, message)
        }
        return true
    }

    @KtorExperimentalAPI
    suspend fun <T : InsightEntity> updateObject(obj: T): T {
        val schema = objectSchemas.first { it.name == mapping[obj::class] }
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

    @KtorExperimentalAPI
    suspend fun <T : InsightEntity> getHistory(obj: T): List<InsightHistoryItem> {
        return JSON.parseArray(httpClient.get<String> {
            url("$BASE_URL/rest/insight/1.0/object/${obj.id}/history")
            contentType(ContentType.Application.Json)
        }, InsightHistoryItem::class.java)
    }

    @KtorExperimentalAPI
    suspend fun <T : InsightEntity> getAttachments(obj: T): List<InsightAttachment> {
        return JSON.parseArray(httpClient.get<String> {
            url("$BASE_URL/rest/insight/1.0/attachments/object/${obj.id}")
            contentType(ContentType.Application.Json)
        }, InsightAttachment::class.java)
    }

    @KtorExperimentalAPI
    suspend fun downloadAttachment(obj: InsightAttachment): ByteArray {
        val url = obj.url
        return httpClient.get {
            url(url)
        }
    }

    @KtorExperimentalAPI
    suspend fun <T : InsightEntity> uploadAttachment(
        obj: T,
        filename: String,
        byteArray: ByteArray,
        comment: String = ""
    ): List<InsightAttachment> {
        val mimeType = URLConnection.guessContentTypeFromName(filename)
        httpClient.post<String> {
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

    @KtorExperimentalAPI
    suspend fun deleteAttachment(attachment: InsightAttachment): String {
        return httpClient.delete {
            url("$BASE_URL/rest/insight/1.0/attachments/${attachment.id}")
        }
    }

    private val log = LoggerFactory.getLogger(InsightCloudApi::class.java)
}
