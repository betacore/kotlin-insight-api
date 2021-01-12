package com.coop.technologies.kotlinInsightApi

import com.alibaba.fastjson.JSON
import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URLConnection
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

object InsightCloudApi {

    private var BASE_URL = "https://insight-api.riada.io"
    private var schemaId: Int = -1

    private val mapping: MutableMap<KClass<out InsightEntity>, String> = mutableMapOf()
    private var pageSize: Int = 50
    var objectSchemas: List<ObjectTypeSchema> = emptyList()
    private var ignoreSubtypes: Boolean = false

    var httpClient: HttpClient = httpClient("", "")

    // One Time Initialization

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

    suspend fun reloadSchema() {
        val (_, schemaBody) = objectSchema(schemaId).httpGet()
        val schemas = JSON.parseArray(schemaBody, ObjectTypeSchema::class.java)
        val fullSchemas = schemas.map {
            val (_, attributeBody) = objectType(it.id).httpGet()
            val attributes = JSON.parseArray(attributeBody, ObjectTypeSchemaAttribute::class.java)
            it.attributes = attributes
            it
        }
        objectSchemas = fullSchemas
    }

    private suspend fun <T : InsightEntity> getObjectsRaw(clazz: KClass<T>): List<InsightObject> {
        return getObjectsRawByIQL(clazz, null)
    }

    private suspend fun <T : InsightEntity> getObjectRaw(clazz: KClass<T>, id: Int): InsightObject? {
        val iql = "objectId=$id"
        return getObjectsRawByIQL(clazz, iql).firstOrNull()
    }

    private suspend fun <T : InsightEntity> getObjectRawByName(clazz: KClass<T>, name: String): InsightObject? {
        val iql = "Name=\"$name\""
        return getObjectsRawByIQL(clazz, iql).firstOrNull()
    }

    private suspend fun <T : InsightEntity> getObjectsRawByIQL(
        clazz: KClass<T>,
        iql: String?
    ): List<InsightObject> {
        log.debug("Getting objects for [${clazz.simpleName}] with [$iql]")
        val objectName = mapping[clazz] ?: ""
        val urlFun: (Int) -> Endpoint = { page: Int ->
            objectsByIql(
                if (ignoreSubtypes) {
                    "objectType=\"$objectName\"${iql?.let { " and $it" }.orEmpty()}"
                } else {
                    "objectType in objectTypeAndChildren(\"$objectName\")${iql?.let { " and $it" }.orEmpty()}"
                },
                schemaId,
                pageSize,
                page
            )
        }

        val (_, body) = urlFun(1).httpGet() // Get the first set of results
        val result = JSON.parseObject(body, InsightObjectEntries::class.java)
        val remainingPages = if (result.pageSize > 1) {
            generateSequence(2) { s -> if (s < result.pageSize) s + 1 else null }
        } else emptySequence()

        val pageContents = remainingPages.toList().flatMap { page ->
            val (_, pageBody) = urlFun(page).httpGet()
            JSON.parseObject(pageBody, InsightObjectEntries::class.java).objectEntries
        }

        log.debug("Returning [${(result.objectEntries + pageContents).size}] objects for [${clazz.simpleName}]")
        return result.objectEntries + pageContents
    }

    suspend fun <T : InsightEntity> getObjects(clazz: KClass<T>): List<T> {
        val objects = getObjectsRaw(clazz)
        return parseInsightObjectsToClass(clazz, objects)
    }

    suspend fun <T : InsightEntity> getObject(clazz: KClass<T>, id: Int): T? {
        val obj = getObjectRaw(clazz, id)
        return parseInsightObjectsToClass(clazz, listOfNotNull(obj)).firstOrNull()
    }

    suspend fun <T : InsightEntity> getObjectByName(clazz: KClass<T>, name: String): T? {
        val obj = getObjectRawByName(clazz, name)
        return parseInsightObjectsToClass(clazz, listOfNotNull(obj)).firstOrNull()
    }

    suspend fun <T : InsightEntity> getObjectByIQL(clazz: KClass<T>, iql: String): List<T> {
        val objects = getObjectsRawByIQL(clazz, iql)
        return parseInsightObjectsToClass(clazz, objects)
    }

    suspend fun <T : InsightEntity> createObject(obj: T): T {
        val schema = objectSchemas.first { it.name == mapping[obj::class] }
        val resolvedObj = resolveReferences(obj)

        val editItem = parseObjectToEditItem(resolvedObj, schema)
        val (_, body) = createObject.httpPost(editItem)
        val jsonObject = JSON.parseObject(body)
        obj.id = jsonObject.getIntValue("id")
        obj.key = jsonObject.getString("objectKey")
        return obj
    }

    suspend fun deleteObject(id: Int): Pair<Int, String> {
        return objectById(id).httpDelete()
    }

    suspend fun createComment(id: Int, message: String): Pair<Int, String> {
        return createComment.httpPost(InsightCommentBody(id, message))
    }

    suspend fun <T : InsightEntity> updateObject(obj: T): T {
        val schema = objectSchemas.first { it.name == mapping[obj::class] }
        val resolvedObj = resolveReferences(obj)

        val editItem = parseObjectToEditItem(resolvedObj, schema)
        val (_, body) = objectById(obj.id).httpPut(editItem)
        val jsonObject = JsonParser.parseString(body).asJsonObject
        obj.id = jsonObject.get("id").asInt
        obj.key = jsonObject.get("objectKey").asString
        return obj
    }


    suspend fun <T : InsightEntity> getHistory(obj: T): MutableList<InsightHistoryItem> {
        val (_, body) = objectHistoryById(obj.id).httpGet()
        return JSON.parseArray(body, InsightHistoryItem::class.java)
    }


    suspend fun <T : InsightEntity> getAttachments(obj: T): MutableList<InsightAttachment> {
        val (_, body) = attachmentByObjectId(obj.id).httpGet()
        return JSON.parseArray(body, InsightAttachment::class.java)
    }


    suspend fun downloadAttachment(obj: InsightAttachment): ByteArray {
        val url = obj.url
        return httpClient.get { url(url) }
    }

    suspend fun <T : InsightEntity> uploadAttachment(
        obj: T,
        filename: String,
        byteArray: ByteArray,
        comment: String = ""
    ): MutableList<InsightAttachment> {
        val mimeType = URLConnection.guessContentTypeFromName(filename)
        val body = MultiPartFormDataContent(
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

        attachmentByObjectId(obj.id).httpPost(
            requestBody = body,
            httpHeaders = mapOf(
                "Connection" to "keep-alive",
                "Cache-Control" to "no-cache"
            )
        )
        return getAttachments(obj)
    }


    suspend fun deleteAttachment(attachment: InsightAttachment): Pair<Int, String> {
        return attachmentById(attachment.id).httpDelete()
    }


    private suspend fun resolveInsightReferences(objectType: String, ids: Set<Int>): List<InsightObject> {
        log.debug("Resolving references for objectType [$objectType]")
        val chunkSize = 50
        val results = ids.chunked(chunkSize).map { idList ->
            val (_, body) = objectsByIql(
                if (ignoreSubtypes) {
                    "objectType=\"$objectType\" and objectId in (${idList.joinToString(",")})"
                } else {
                    "objectType in objectTypeAndChildren(\"$objectType\") and objectId in (${idList.joinToString(",")})"
                },
                schemaId,
                chunkSize
            ).httpGet()
            JSON.parseObject(body, InsightObjectEntries::class.java).objectEntries
        }
        log.debug("Resolved references for objectType [$objectType]")
        return results.flatten()
    }


    private suspend fun <T : InsightEntity> parseInsightObjectsToClass(
        clazz: KClass<T>,
        objects: List<InsightObject>
    ): List<T> {
        log.debug("Collecting references for objects of type [${clazz.simpleName}]")
        val refs = buildReferenceMap(objects, clazz).resolveReferences(clazz)

        log.debug("Parsing objects of type [${clazz.simpleName}]")
        return objects.map { obj ->
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
    private fun <S : Any> KClass<*>.toSuperclass(superclass: KClass<S>): KClass<S> =
        if (this.isSubclassOf(superclass))
            this as KClass<S>
        else throw IllegalStateException("Not subclass of $superclass")


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
                        referenceType == InsightEntity::class -> {
                            field to ref.objects.map { InsightEntity(it.first, it.second, "") }
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

    private suspend fun <T : InsightEntity> parseObject(
        clazz: KClass<T>,
        fields: Map<String, KClass<out Any>>,
        values: Map<String?, Any?>,
        references: Map<String?, InsightReference<T>?>,
        referencedObjects: Map<String?, List<InsightEntity>>
    ): T {
        val result = clazz.primaryConstructor
            ?.parameters
            ?.map { pairParameterWithValue(values, references, fields, referencedObjects, it) }?.toMap()
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

    private suspend fun <T : InsightEntity> pairParameterWithValue(
        values: Map<String?, Any?>,
        references: Map<String?, InsightReference<T>?>,
        fields: Map<String, KClass<out Any>>,
        referencedObjects: Map<String?, List<InsightEntity>>,
        parameter: KParameter
    ): Pair<KParameter, Any?> {
        val value = values[parameter.name?.capitalize()]
        val reference = references[parameter.name?.capitalize()]
        val definedClass = fields[parameter.name?.capitalize()]
        val result = when {
            definedClass.isPrimitive() -> definedClass.transformPrimitive(value)
            definedClass.isList(reference == null) -> transformList(parameter, value)
            value.isReference(reference == null) -> insertReferenced(referencedObjects, parameter, reference)
            definedClass != null && value == null && reference == null -> null // null remains null
            else -> throw NotImplementedError("cls: $definedClass - value: $value - reference: $reference")
        }
        return (parameter to result)
    }

    private fun <T : InsightEntity> insertReferenced(
        referencedObjects: Map<String?, List<InsightEntity>>,
        parameter: KParameter,
        reference: InsightReference<T>?
    ): Any? {
        val referenceObjects = referencedObjects[parameter.name?.capitalize()]
        val insightObjects = reference?.objects?.map { it.first }
        val intermediate = insightObjects?.flatMap { referenceId ->
            referenceObjects?.filter { it.id == referenceId }.orEmpty()
        }.orEmpty()

        return if (reference?.clazzToParse == List::class.java) intermediate
        else intermediate.firstOrNull()
    }

    private suspend fun transformList(
        parameter: KParameter,
        value: Any?
    ): List<Any?> {
        val outClass = parameter.type.arguments.first().type?.jvmErasure
        return when {
            outClass.isPrimitive() -> (value as List<Any?>?).orEmpty().map { outClass.transformPrimitive(it) }
            else -> {
                if (mapping.keys.contains(outClass))
                    (value as List<InsightObject>?).orEmpty().flatMap {
                        parseInsightObjectsToClass(
                            mapping.keys.first { key -> key == outClass },
                            listOf(it)
                        )
                    }
                else TODO("Unknown outClass for List: ${outClass?.simpleName}")
            }
        }
    }

    /* Not sure if this actually does anything
     */
    private suspend fun <T : InsightEntity> resolveReferences(obj: T): T {
        obj::class.memberProperties.map {
            it as KProperty1<Any, *>
        }.filter { property ->
            property.get(obj)?.javaClass?.kotlin?.isSubclassOf(InsightEntity::class) == true
        }.onEach {
            val item = it.get(obj) as InsightEntity
            if (item.id == -1 || item.key.isBlank()) {
                // get entity by name, if not exists create
                val resolvedObject = getObjectByName(item::class, it.name) ?: createObject(item)
                item.id = resolvedObject.id
                item.key = resolvedObject.key
            }
        }
        return obj
    }

    private fun <T : InsightEntity> parseObjectToEditItem(
        obj: T,
        schema: ObjectTypeSchema
    ): ObjectEditItem {
        fun <X> KProperty1<out T, Any?>.value(obj: T): X? =
            (this as KProperty1<T, X>?)?.get(obj)

        val attributes: List<ObjectEditItemAttribute> = obj::class.declaredMemberProperties.mapNotNull { property ->
            val values = when {
                property.returnType.jvmErasure.isSubclassOf(InsightEntity::class) -> listOf(
                    property.value<InsightEntity>(
                        obj
                    )?.key
                )
                property.returnType.jvmErasure == List::class -> {
                    property.value<List<*>>(obj)?.mapNotNull { item ->
                        if (item!!::class.isSubclassOf(InsightEntity::class)) {
                            (item as InsightEntity).key
                        } else item
                    }
                }
                else -> listOf(property.value<Any>(obj))
            }.orEmpty()

            schema.attributes
                .orEmpty()
                .firstOrNull { it.name == property.name.capitalize() }
                ?.let {
                    ObjectEditItemAttribute(
                        it.id,
                        values.mapNotNull { item -> ObjectEditItemAttributeValue(item) })
                }
        }
        log.debug("ParsedObject: [$attributes]")
        return ObjectEditItem(schema.id, attributes)
    }

    private val basePath = listOf("rest", "insight", "1.0")
    private val createComment = Endpoint(basePath + listOf("comment", "create"))
    private val createObject = Endpoint(basePath + listOf("object", "create"))

    private fun objectSchema(schemaId: Int): Endpoint =
        Endpoint(
            basePath + listOf("objectschema", "$schemaId", "objecttypes", "flat")
        )

    private fun objectType(schemaId: Int): Endpoint =
        Endpoint(
            basePath + listOf("objecttype", "$schemaId", "attributes")
        )

    private fun objectsByIql(iql: String, schemaId: Int, pageSize: Int): Endpoint =
        Endpoint(
            basePath + listOf("iql", "objects"),
            mapOf(
                "iql" to iql,
                "objectSchemaId" to "$schemaId",
                "resultPerPage" to "$pageSize",
                "includeTypeAttributes" to "true"
            )
        )

    private fun objectsByIql(iql: String, schemaId: Int, pageSize: Int, page: Int): Endpoint =
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

    private fun objectById(id: Int): Endpoint =
        Endpoint(
            basePath + listOf("object", "$id")
        )

    private fun objectHistoryById(id: Int): Endpoint =
        Endpoint(
            basePath + listOf("object", "history", "$id")
        )

    private fun attachmentByObjectId(objectId: Int): Endpoint =
        Endpoint(
            basePath + listOf("attachments", "object", "$objectId")
        )

    private fun attachmentById(attachmentId: Int): Endpoint =
        Endpoint(
            basePath + listOf("attachments", "$attachmentId")
        )

    private suspend fun handleResult(result: HttpResponse): Pair<Int, String> =
        when (result.status.value) { // TODO Improve error handling
            in 200..299 -> result.status.value to result.readText()
            in 400..499 -> {
                val errorResponse = JSON.parseObject(result.readText(), InsightErrorResponse::class.java)
                throw IllegalStateException("${errorResponse.errors.values}", ResponseException(result))
            }
            in 500..599 -> throw ResponseException(result)
            else -> throw ResponseException(result)
        }

    private suspend fun Endpoint.httpGet(httpHeaders: Map<String, String> = emptyMap()): Pair<Int, String> {
        val result = httpClient.get<HttpResponse>(this.toUrl(BASE_URL)) {
            headers { httpHeaders.onEach { this.append(it.key, it.value) } }
        }
        return handleResult(result)
    }

    private suspend fun Endpoint.httpPost(
        requestBody: Any,
        httpHeaders: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        val result = httpClient.post<HttpResponse>(this.toUrl(BASE_URL)) {
            headers { httpHeaders.onEach { this.append(it.key, it.value) } }
            contentType(ContentType.Application.Json)
            body = JSON.toJSONString(requestBody)
        }
        return handleResult(result)
    }

    private suspend fun Endpoint.httpPut(
        requestBody: Any,
        httpHeaders: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        val result = httpClient.put<HttpResponse>(this.toUrl(BASE_URL)) {
            headers { httpHeaders.onEach { this.append(it.key, it.value) } }
            contentType(ContentType.Application.Json)
            body = JSON.toJSONString(requestBody)
        }
        return handleResult(result)
    }

    private suspend fun Endpoint.httpDelete(httpHeaders: Map<String, String> = emptyMap()): Pair<Int, String> {
        val result = httpClient.delete<HttpResponse>(this.toUrl(BASE_URL)) {
            headers { httpHeaders.onEach { this.append(it.key, it.value) } }
            contentType(ContentType.Application.Json)
        }
        return handleResult(result)
    }

    private val log = LoggerFactory.getLogger(InsightCloudApi::class.java)
}
