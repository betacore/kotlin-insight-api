package com.coop.technologies.kotlinInsightApi

object InsightCloudApi {

    private val api = InsightUtils

    // One Time Initialization
    fun init(schemaId: Int, authToken: String) {
        api.init(schemaId, authToken)
    }

    fun registerClass(clazz: Class<out InsightEntity>, objectName: String) {
        api.mapping[clazz] = objectName
    }

    suspend fun <T: InsightEntity> getObjects(clazz: Class<T>): List<T> =
        api.getObjects(clazz)

    suspend fun <T: InsightEntity> getObject(clazz: Class<T>, id: Int): T =
        api.getObject(clazz, id)

    suspend fun <T> getFilteredObjects(clazz: Class<T>, iql: String): List<T> =
        throw NotImplementedError()

    suspend fun <T> createObject(obj: T): T =
        throw NotImplementedError()

    suspend fun <T> deleteObject(clazz: Class<T>, id: Int): Boolean =
        throw NotImplementedError()

    suspend fun <T> updateObject(obj: T, id: Int): T =
        throw NotImplementedError()


}