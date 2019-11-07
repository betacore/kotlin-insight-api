package com.coop.technologies.kotlinInsightApi

data class InsightName(
    val value: String
)

data class InsightId(
    val value: Int
)

abstract class InsightEntity() {
    abstract val id: Int
}


//suspend fun InsightEntity.save() {
//    InsightCloudApi.updateObject(this, this.id)
//}