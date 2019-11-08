package com.coop.technologies.kotlinInsightApi

data class InsightName(
    val value: String
)

data class InsightId(
    val value: Int
)

abstract class InsightEntity {
    var id: Int = -1
    var key: String = ""
    abstract val name: String
}

data class ObjectTypeSchema(
    val id: Int,
    val name: String,
    var attributes: List<ObjectTypeSchemaAttribute>
)

data class ObjectEditItem(
    val objectTypeId: Int,
    val attributes: List<ObjectEditItemAttribute>
)

data class ObjectEditItemAttribute(
    val objectTypeAttributeId: Int,
    val objectAttributeValues: List<ObjectEditItemAttributeValue>
)

data class ObjectEditItemAttributeValue(
    val value: Any?
)

data class ObjectTypeSchemaAttribute(
    val id: Int,
    val name: String
)

data class InsightObjectEntries(
    val objectEntries: List<InsightObject>
)

data class InsightObject(
    val id: Int,
    val label: String,
    val objectKey: String,
    val objectType: ObjectType,
    val attributes: List<InsightAttribute>
)

data class ObjectType(
    val id: Int,
    val name: String,
    val objectSchemaId: Int
)

data class InsightObjectAttribute(
    val id: Int,
    val objectTypeAttribute: List<InsightAttribute>
)

data class InsightAttribute(
    val id: Int,
    val objectTypeAttribute: ObjectTypeAttribute?,
    val objectTypeAttributeId: Int,
    val objectId: Int,
    val objectAttributeValues: List<ObjectAttributeValue>
)

data class ObjectTypeAttribute (
    val id: Int,
    val name: String,
    val referenceObjectTypeId: Int,
    val referenceObjectType: ObjectType
)

data class InsightReference(
    val objectType: String,
    val objectId: Int,
    val clazzToParse: Class<out InsightEntity>
)

data class ObjectAttributeValue(
    val value: Any?,
    val displayValude: Any?
)

data class InsightAttributeEntry (
    val value : Any
)

//suspend fun InsightEntity.save() {
//    InsightCloudApi.updateObject(this, this.id)
//}