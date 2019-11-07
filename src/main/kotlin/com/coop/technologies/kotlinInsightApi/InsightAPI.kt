package com.coop.technologies.kotlinInsightApi

import com.google.gson.JsonParser
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

object InsightCloudApi {

    private val BASE_URL = "https://insight-api.riada.io"
    private var schemaId: Int = -1
    private var authToken: String = ""
    val mapping: MutableMap<Class<out InsightEntity>, String> = mutableMapOf()

    // One Time Initialization
    fun init(schemaId: Int, authToken: String) {
        this.schemaId = schemaId
        this.authToken = authToken
    }

    fun registerClass(clazz: Class<out InsightEntity>, objectName: String) {
        this.mapping[clazz] = objectName
    }

    suspend fun getObjects(objType: String): List<InsightObject> {
        val objects = httpClient().get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=$objType&includeTypeAttributes=true")
            header("Authorization", "Bearer $authToken")
        }
        return objects.objectEntries
    }

    suspend fun getObject(objType: String, id: Int): InsightObject {
        val objects = httpClient().get<InsightObjectEntries> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=$objType and objectId=$id&includeTypeAttributes=true")
            header("Authorization", "Bearer $authToken")
        }
        return objects.objectEntries.first()
    }

    suspend fun <T : InsightEntity> parseInsightObjectToClass(clazz:Class<T>, obj: InsightObject): T {
        val fieldsMap = clazz.declaredFields.map {
            it.name.capitalize() to it.type
        }.toMap()
        val id = listOf("Id" to obj.id).toMap()
        val values = obj.attributes.filter { it.objectTypeAttribute?.referenceObjectType == null }.map {
            it.objectTypeAttribute?.name to it.objectAttributeValues.first().value
        }.toMap()
        val references = obj.attributes.filter{ it.objectTypeAttribute?.referenceObjectType != null }.map {
            it.objectTypeAttribute?.referenceObjectType?.name to
                    (fieldsMap.get(it.objectTypeAttribute?.referenceObjectType?.name?:"")?.let { Class.forName(it.name)}?.let { it1 ->
                        InsightReference(
                            objectType = it.objectTypeAttribute?.referenceObjectType?.name?:"",
                            objectId = it.objectTypeAttribute?.referenceObjectTypeId?:0,
                            clazzToParse = it1 as Class<T>
                        )
                    })
        }.toMap()
        val allValues = id + values
        return parseObject(clazz, fieldsMap, allValues, references)
    }

    private suspend fun <T: InsightEntity> parseObject(clazz: Class<T>, fields: Map<String, Class<out Any?>>, values: Map<String?, Any?>, references: Map<String?, InsightReference?>): T {
        val kobj = Class.forName(clazz.name).kotlin
        val result = kobj.primaryConstructor
            ?.parameters
            ?.fold(emptyMap<KParameter, Any?>()) { map, parameter ->
                val value = values.get(parameter.name?.capitalize())
                val reference = references.get(parameter.name?.capitalize())
                val definedClass = fields.get(parameter.name?.capitalize())
                var result = value
                if(value == null && reference != null){
                    val referenceObject = references.get(parameter.name?.capitalize())
                    val insightObject = getObject(referenceObject?.objectType?:"", referenceObject?.objectId?:0)
                    if(InsightEntity::class.java in Class.forName(reference?.clazzToParse?.name).interfaces){
                        val parsedObject = parseInsightObjectToClass(referenceObject?.clazzToParse as Class<T>, insightObject)
                        result = parsedObject
                    } else if(reference.clazzToParse == String::class.java){
                        result = insightObject.attributes.filter { it.objectTypeAttribute?.name == "Name" }.first().objectAttributeValues.first().value
                    } else if(reference.clazzToParse == Int::class.java) {
                        result = insightObject.id
                    }
                }
                map + (parameter to result)
            }?.let {
                kobj.primaryConstructor?.callBy(it) as T
            } ?: throw RuntimeException("Object ${clazz.name} could not be loaded")
        return result
    }

    suspend fun <T: InsightEntity> getObjects(clazz: Class<T>): List<T> =
        throw NotImplementedError()

    suspend fun <T: InsightEntity> getObject(clazz: Class<T>, id: Int): T =
        throw NotImplementedError()

    suspend fun <T> getFilteredObjects(clazz: Class<T>, iql: String): List<T> =
        throw NotImplementedError()

    suspend fun <T> createObject(obj: T): T =
        throw NotImplementedError()

    suspend fun <T> deleteObject(clazz: Class<T>, id: Int): Boolean =
        throw NotImplementedError()

    suspend fun <T> updateObject(obj: T, id: Int): T =
        throw NotImplementedError()


}