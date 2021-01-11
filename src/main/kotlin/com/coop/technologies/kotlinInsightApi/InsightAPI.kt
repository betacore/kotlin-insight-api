package com.coop.technologies.kotlinInsightApi

import com.alibaba.fastjson.JSON
import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
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

suspend fun buildEnvironment(
    schemaId: Int,
    url: String,
    username: String,
    password: String,
    mapping: Map<KClass<out InsightEntity>, String>,
    pageSize: Int = 50,
    ignoreSubtypes: Boolean = false
): Either<HttpError, ExecutionEnvironment> {
    val httpClient = httpClient(username, password)
    return reloadSchema(schemaId, url, httpClient).map {
        ExecutionEnvironment(
            pageSize = pageSize,
            baseUrl = url,
            schemaId = schemaId,
            ignoreSubtypes = ignoreSubtypes,
            httpClient = httpClient,
            objectSchemas = it,
            mapping = mapping
        )
    }
}

suspend fun reloadSchema(
    schemaId: Int,
    baseUrl: String,
    httpClient: HttpClient
): Either<HttpError, List<ObjectTypeSchema>> {
    val schemaBody = objectSchema(schemaId).httpGet(baseUrl, httpClient)
    val schemas = schemaBody.map { JSON.parseArray(it, ObjectTypeSchema::class.java) }
    return schemas.bind { list ->
        list.map { schema ->
            val attributeBody = objectType(schema.id).httpGet(baseUrl, httpClient)
            attributeBody.map {
                schema.copy(attributes = JSON.parseArray(it, ObjectTypeSchemaAttribute::class.java))
            }
        }.sequence()
    }
}

private suspend fun <T : InsightEntity> ExecutionEnvironment.getObjectsRaw(clazz: KClass<T>): Either<HttpError, List<InsightObject>> {
    return getObjectsRawByIQL(clazz, null)
}

private suspend fun <T : InsightEntity> ExecutionEnvironment.getObjectRaw(
    clazz: KClass<T>,
    id: Int
): Either<HttpError, InsightObject?> {
    val iql = "objectId=$id"
    return getObjectsRawByIQL(clazz, iql).map { it.firstOrNull() }
}

private suspend fun <T : InsightEntity> ExecutionEnvironment.getObjectRawByName(
    clazz: KClass<T>,
    name: String
): Either<HttpError, InsightObject?> {
    val iql = "Name=\"$name\""
    return getObjectsRawByIQL(clazz, iql).map { it.firstOrNull() }
}

private suspend fun <T : InsightEntity> ExecutionEnvironment.getObjectsRawByIQL(
    clazz: KClass<T>,
    iql: String?
): Either<HttpError, List<InsightObject>> {
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

    return urlFun(1).httpGet(baseUrl, httpClient).bind { body ->
        val result = JSON.parseObject(body, InsightObjectEntries::class.java)
        val remainingPages = if (result.pageSize > 1) {
            generateSequence(2) { s -> if (s < result.pageSize) s + 1 else null }
        } else emptySequence()

        val pageContents = remainingPages.toList().map { page ->
            urlFun(page).httpGet(baseUrl, httpClient)
                .map {
                    JSON.parseObject(it, InsightObjectEntries::class.java).objectEntries
                }
        }.sequence().map { it.flatten() }

        log.debug("Returning [${(result.objectEntries + pageContents).size}] objects for [${clazz.simpleName}]")
        pageContents.map { result.objectEntries + it }
    }
}

suspend fun <T : InsightEntity> ExecutionEnvironment.getObjects(clazz: KClass<T>): Either<HttpError, List<T>> {
    return getObjectsRaw(clazz).bind { objects -> parseInsightObjectsToClass(clazz, objects) }
}

suspend fun <T : InsightEntity> ExecutionEnvironment.getObject(clazz: KClass<T>, id: Int): Either<HttpError, T?> {
    return getObjectRaw(clazz, id).bind { obj ->
        parseInsightObjectsToClass(clazz, listOfNotNull(obj)).map { it.firstOrNull() }
    }
}

suspend fun <T : InsightEntity> ExecutionEnvironment.getObjectByName(
    clazz: KClass<T>,
    name: String
): Either<HttpError, T?> {
    return getObjectRawByName(clazz, name).bind { obj ->
        parseInsightObjectsToClass(clazz, listOfNotNull(obj)).map { it.firstOrNull() }
    }
}

suspend fun <T : InsightEntity> ExecutionEnvironment.getObjectByIQL(
    clazz: KClass<T>,
    iql: String
): Either<HttpError, List<T>> {
    return getObjectsRawByIQL(clazz, iql).bind { objects ->
        parseInsightObjectsToClass(clazz, objects)
    }
}

suspend fun <T : InsightEntity> ExecutionEnvironment.createObject(obj: T): Either<HttpError, T> {
    val schema = objectSchemas.first { it.name == mapping[obj::class] }
    val resolvedObj = resolveReferences(obj)

    val editItem = parseObjectToEditItem(resolvedObj, schema)
    return createObject.httpPost(editItem, baseUrl, httpClient)
        .map { body ->
            val jsonObject = JSON.parseObject(body)
            obj.id = jsonObject.getIntValue("id")
            obj.key = jsonObject.getString("objectKey")
            obj
        }
}

suspend fun ExecutionEnvironment.deleteObject(id: Int): Either<HttpError, String> {
    return objectById(id).httpDelete(baseUrl, httpClient)
}

suspend fun ExecutionEnvironment.createComment(id: Int, message: String): Either<HttpError, String> {
    return createComment.httpPost(InsightCommentBody(id, message), baseUrl, httpClient)
}

suspend fun <T : InsightEntity> ExecutionEnvironment.updateObject(obj: T): Either<HttpError, T> {
    val schema = objectSchemas.first { it.name == mapping[obj::class] }
    val resolvedObj = resolveReferences(obj)

    val editItem = parseObjectToEditItem(resolvedObj, schema)
    return objectById(obj.id).httpPut(editItem, baseUrl, httpClient)
        .map { body ->
            val jsonObject = JsonParser.parseString(body).asJsonObject
            obj.id = jsonObject.get("id").asInt
            obj.key = jsonObject.get("objectKey").asString
            obj
        }
}


suspend fun <T : InsightEntity> ExecutionEnvironment.getHistory(obj: T): Either<HttpError, List<InsightHistoryItem>> {
    return objectHistoryById(obj.id).httpGet(baseUrl, httpClient).map { body ->
        JSON.parseArray(body, InsightHistoryItem::class.java)
    }

}


suspend fun <T : InsightEntity> ExecutionEnvironment.getAttachments(obj: T): Either<HttpError, List<InsightAttachment>> {
    return attachmentByObjectId(obj.id).httpGet(baseUrl, httpClient)
        .map { body ->
            JSON.parseArray(body, InsightAttachment::class.java)
        }
}


suspend fun ExecutionEnvironment.downloadAttachment(obj: InsightAttachment): ByteArray {
    val url = obj.url
    return httpClient.get { url(url) }
}

suspend fun <T : InsightEntity> ExecutionEnvironment.uploadAttachment(
    obj: T,
    filename: String,
    byteArray: ByteArray,
    comment: String = ""
): Either<HttpError, List<InsightAttachment>> {
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
        ),
        baseUrl = baseUrl,
        httpClient = httpClient
    )
    return getAttachments(obj)
}


suspend fun ExecutionEnvironment.deleteAttachment(attachment: InsightAttachment): Either<HttpError, String> {
    return attachmentById(attachment.id).httpDelete(baseUrl, httpClient)
}


private suspend fun ExecutionEnvironment.resolveInsightReferences(
    objectType: String,
    ids: Set<Int>
): Either<HttpError, List<InsightObject>> {
    log.debug("Resolving references for objectType [$objectType]")
    val chunkSize = 50
    val results = ids.chunked(chunkSize).map { idList ->
        objectsByIql(
            if (ignoreSubtypes) {
                "objectType=\"$objectType\" and objectId in (${idList.joinToString(",")})"
            } else {
                "objectType in objectTypeAndChildren(\"$objectType\") and objectId in (${idList.joinToString(",")})"
            },
            schemaId,
            chunkSize
        ).httpGet(baseUrl, httpClient)
            .map { body ->
                JSON.parseObject(body, InsightObjectEntries::class.java).objectEntries
            }
    }.sequence().map { it.flatten() }
    log.debug("Resolved references for objectType [$objectType]")
    return results
}


private suspend fun <T : InsightEntity> ExecutionEnvironment.parseInsightObjectsToClass(
    clazz: KClass<T>,
    objects: List<InsightObject>
): Either<HttpError, List<T>> {
    log.debug("Collecting references for objects of type [${clazz.simpleName}]")
    val ref = resolveReferences(buildReferenceMap(objects, clazz), clazz)

    log.debug("Parsing objects of type [${clazz.simpleName}]")
    return ref.map { refs ->
        objects.map { obj ->
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
}

@Suppress("UNCHECKED_CAST") // casting to superclass is always possible
private fun <S : Any> KClass<*>.toSuperclass(superclass: KClass<S>): KClass<S> =
    if (this.isSubclassOf(superclass))
        this as KClass<S>
    else throw IllegalStateException("Not subclass of $superclass")


private suspend fun <T : InsightEntity> ExecutionEnvironment.resolveReferences(
    referenceMap: Map<String?, InsightReference<T>?>,
    clazz: KClass<T>
): Either<HttpError, Map<String?, List<InsightEntity>>> =
    referenceMap.mapNotNull { (field, ref) ->
        log.trace("Resolving Reference for field $field")
        when (ref?.clazzToParse) {
            null -> (null to emptyList<InsightEntity>()).right()
            List::class.java -> {
                val referenceType =
                    clazz.primaryConstructor
                        ?.parameters
                        ?.first { it.name?.capitalize() == field }
                        ?.type
                        ?.arguments?.firstOrNull()?.type?.jvmErasure
                val x: Either<HttpError, Pair<String?, List<InsightEntity>>> = when {
                    referenceType == InsightEntity::class -> {
                        (field to ref.objects.map { InsightEntity(it.first, it.second, "") }).right()
                    }
                    referenceType?.isSubclassOf(InsightEntity::class) == true ->
                        resolveInsightReferences(
                            ref.objectType,
                            ref.objects.map { it.first }.toSet()
                        ).bind { objects ->
                            parseInsightObjectsToClass(
                                referenceType!!.toSuperclass(InsightEntity::class),
                                objects
                            ).map { field to it }
                        }
                    else -> Pair(null, emptyList<InsightEntity>()).right()
                }
                x.map { it }
            }
            else -> {
                ref.let { ref ->
                    resolveInsightReferences(ref.objectType, ref.objects.map { it.first }.toSet())
                        .bind { objects ->
                            parseInsightObjectsToClass(
                                ref.clazzToParse,
                                objects
                            ).map { field to it }
                        }
                }
            }
        }
    }.sequence().map { it.toMap() }


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

private suspend fun <T : InsightEntity> ExecutionEnvironment.parseObject(
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

private suspend fun <T : InsightEntity> ExecutionEnvironment.pairParameterWithValue(
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

private suspend fun ExecutionEnvironment.transformList(
    parameter: KParameter,
    value: Any?
): Either<HttpError, List<Any?>> {
    val outClass = parameter.type.arguments.first().type?.jvmErasure
    return when {
        outClass.isPrimitive() -> (value as List<Any?>?).orEmpty().map { outClass.transformPrimitive(it) }.right()
        else -> {
            if (mapping.keys.contains(outClass))
                (value as List<InsightObject>?).orEmpty().map {
                    parseInsightObjectsToClass(
                        mapping.keys.first { key -> key == outClass },
                        listOf(it)
                    )
                }.sequence().map { it.flatten() }
            else TODO("Unknown outClass for List: ${outClass?.simpleName}")
        }
    }
}

/* Not sure if this actually does anything
 */
private suspend fun <T : InsightEntity> ExecutionEnvironment.resolveReferences(obj: T): T {
    obj::class.memberProperties.map {
        it as KProperty1<Any, *>
    }.filter { property ->
        property.get(obj)?.javaClass?.kotlin?.isSubclassOf(InsightEntity::class) == true
    }.onEach {
        val item = it.get(obj) as InsightEntity
        if (item.id == -1 || item.key.isBlank()) {
            // get entity by name, if not exists create
            getObjectByName(item::class, it.name).bind { objectOrNull ->
                objectOrNull?.right() ?: createObject(item)
            }.map { entity ->
                item.id = entity.id
                item.key = entity.key
            }
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

private val log = LoggerFactory.getLogger("com.coop.technologies.kotlinInsightApi.InsightApi")


