import com.coop.technologies.kotlinInsightApi.*
import com.typesafe.config.ConfigFactory
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking

class Company(
    override val id: Int,
    val name: String,
    val country: InsightName
) : InsightEntity()

class Company2(
    override val id: Int,
    val name: String,
    val country: Country
) : InsightEntity()

data class Country(
    override val id: Int,
    val name: String
) : InsightEntity()

class MainTest : TestCase() {

    override fun setUp() {
        super.setUp()
        println("#### Starting setUp")
        val config = ConfigFactory.parseResources("test.conf").resolve()
        val authToken = config.getString("conf.authToken")
        InsightCloudApi.init(1, authToken)
        InsightCloudApi.registerClass(Company::class.java, "Company")
        InsightCloudApi.registerClass(Company2::class.java, "Company")
        InsightCloudApi.registerClass(Country::class.java, "Country")
    }

    fun test1() {
        println("#### Starting test1")
        val companies = runBlocking {
            InsightCloudApi.getObjects(Company::class.java)
        }
        assertTrue(companies.size == 1)
        assertTrue(companies.first().name == "Test Gmbh")
        assertTrue(companies.first().country.value == "Germany")
    }

    fun test2(){
        println("#### Starting test2")
        val companies = runBlocking {
            InsightCloudApi.getObjects(Company2::class.java)
        }
        assertTrue(companies.size == 1)
        assertTrue(companies.first().id == 1)
        assertTrue(companies.first().name == "Test Gmbh")
        assertTrue(companies.first().country.id == 4)
        assertTrue(companies.first().country.name == "Germany")
    }
}