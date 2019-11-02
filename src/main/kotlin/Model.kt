import kotlinx.coroutines.runBlocking

interface InsightModel

data class InsightName(
    val value: String
)

data class InsightId(
    val value: Int
)

abstract class InsightEntity {
    abstract val id: Int
    abstract val name: String
}

//suspend fun InsightEntity.save() {
//    InsightCloudApi.updateObject(this, this.id)
//}