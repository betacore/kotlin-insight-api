package com.coop.technologies.kotlinInsightApi

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

object InsightUtils {
    // Constants
    private val BASE_URL = "https://insight-api.riada.io"
    private var schemaId: Int = -1
    private var authToken: String = ""

    fun init(schemaId: Int, authToken: String) {
        this.schemaId = schemaId
        this.authToken = authToken
    }

    private suspend fun <T> parseObject(clazz: Class<T>, jsonObject: JsonObject): T {
        val fieldsMap = clazz.declaredFields.map {
            it.name.capitalize() to it.type
        }.toMap()
        val valuesMap = mapAttributes(jsonObject, fieldsMap)
        val kobj = Class.forName(clazz.name).kotlin
        val result = kobj.primaryConstructor
            ?.parameters
            ?.fold(emptyMap<KParameter, Any?>()) { map, parameter ->
                map + (parameter to valuesMap.get(parameter.name?.capitalize()))
            }?.let {
                kobj.primaryConstructor?.callBy(it) as T
            } ?: throw RuntimeException("Object ${clazz.name} could not be loaded")
        return result
    }

    suspend fun <T> getObjects(clazz: Class<T>): List<T> {
        val objectName = clazz.simpleName.replace(Regex("[0-9]"),"").split("(?=\\p{Upper})")[0].capitalize()
        val jsonArray = requestInsightObjects(objectName)
        val objects = jsonArray.map {
            parseObject(clazz, it.asJsonObject)
        }
        return objects
    }

    suspend fun <T> getObject(clazz: Class<T>, id: Int): T {
        val objectName = clazz.simpleName.replace(Regex("[0-9]"),"").split("(?=\\p{Upper})")[0].capitalize()
        val jsonArray = requestInsightObject(objectName, id)
        val obj = parseObject(clazz, jsonArray.first().asJsonObject)
        return obj
    }

    private suspend fun requestInsightObjects(objType: String): JsonArray {
        val json = httpClient().get<String> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=$objType&includeTypeAttributes=true")
            header("Authorization", "Bearer $authToken")
        }
        return JsonParser().parse(json).asJsonObject.get("objectEntries").asJsonArray
    }

    private suspend fun requestInsightObject(objType: String, id: Int): JsonArray {
        val json = httpClient().get<String> {
            url("$BASE_URL/rest/insight/1.0/iql/objects?objectSchemaId=$schemaId&iql=objectType=$objType and objectId=$id&includeTypeAttributes=true")
            header("Authorization", "Bearer $authToken")
        }
        return JsonParser().parse(json).asJsonObject.get("objectEntries").asJsonArray
    }

    private suspend fun mapAttributes(
        jsonObject: JsonObject,
        fieldsMap: Map<String, Class<out Any>>
    ): Map<String, Any?> =
        jsonObject.get("attributes").asJsonArray.filter {
            val attributeName = it.asJsonObject.get("objectTypeAttribute").asJsonObject.get("name").asString
            fieldsMap.containsKey(attributeName)
        }
            .map {
                val attributeName = it.asJsonObject.get("objectTypeAttribute").asJsonObject.get("name").asString
                val attributeType = fieldsMap[attributeName] ?: String::class.java
                val jsonValue =
                    it.asJsonObject.get("objectAttributeValues").asJsonArray.get(0).asJsonObject.get("value")
                val jsonReference =
                    it.asJsonObject.get("objectAttributeValues").asJsonArray.get(0).asJsonObject.get("referencedObject")
                val objId = jsonReference?.asJsonObject?.get("id")?.asInt ?: -1
                val objName = jsonReference?.asJsonObject?.get("name")?.asString ?: ""
                val value = when {
                    attributeType.superclass == InsightModel::class.java -> getObject(attributeType as Class<InsightEntity>, objId)
                    attributeType == InsightName::class.java -> InsightName(objName)
                    attributeType == InsightId::class.java -> InsightId(objId)
                    else -> getAsType(jsonValue, attributeType::class.java)
                }
                attributeName to value
            }.toMap()

    private fun <R> getAsType(attribute: JsonElement?, attributeType: Class<R>): Any? {
        val value = attribute ?: return null
        return when (attributeType::javaClass) {
            Int::class.java -> value.asInt
            Double::class.java -> value.asDouble
            Float::class.java -> value.asFloat
            else -> value.asString
        }
    }
}