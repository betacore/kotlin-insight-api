import com.coop.technologies.kotlinInsightApi.*
import com.typesafe.config.ConfigFactory
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking

data class Company(
    val name: String,
    val country: InsightName
) : InsightModel

data class Company2(
    val id: Int,
    val name: String,
    val country: Country
): InsightModel

data class Country(
    val id: Int,
    val name: String
)

class MainTest : TestCase() {

    override fun setUp() {
        super.setUp()
        val config = ConfigFactory.parseResources("test.conf").resolve()
        val authToken = config.getString("conf.authToken")
        InsightCloudApi.init(1, authToken)
    }

    fun test1(){
        val companies = runBlocking {
            InsightCloudApi.getObjects(Company::class.java)
        }
        assertTrue(companies.size == 1)
        assertTrue(companies.first().name == "Test Gmbh")
        assertTrue(companies.first().country.value == "Germany")
    }

//    fun test2(){
//        val companies = runBlocking {
//            InsightCloudApi.getObjects(Company2::class.java)
//        }
//        assertTrue(companies.size == 1)
//        assertTrue(companies.first().id == 1)
//        assertTrue(companies.first().name == "Test Gmbh")
//        assertTrue(companies.first().country.id == 4)
//        assertTrue(companies.first().country.name == "Germany")
//    }
}