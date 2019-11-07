import com.coop.technologies.kotlinInsightApi.*
import com.typesafe.config.ConfigFactory
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking

data class Company(
    val id: Int,
    val name: String,
    val country: String
) : InsightEntity

data class Company2(
    val id: Int,
    val name: String,
    val country: Country
) : InsightEntity

data class Country(
    val id: Int,
    val name: String,
    val shortName: String
): InsightEntity

class MainTest : TestCase() {

    override fun setUp() {
        super.setUp()
        println("#### Starting setUp")
        val config = ConfigFactory.parseResources("test.conf").resolve()
        val authToken = config.getString("conf.authToken")
        InsightCloudApi.init(1, authToken)
    }

    fun test1(){
        val objs = runBlocking {
            InsightCloudApi.getObjects("Company")
        }
        assertTrue(objs.size == 1)
        val companies = objs.map {
            runBlocking { InsightCloudApi.parseInsightObjectToClass(Company::class.java, it) }
        }
        assertTrue(companies.size == 1)
        val company = companies.first()
        assertTrue(company.id == 1)
        assertTrue(company.name == "Test Gmbh")
        assertTrue(company.country == "Germany")
    }

    fun test2(){
        val objs = runBlocking {
            InsightCloudApi.getObjects("Company")
        }
        assertTrue(objs.size == 1)
        val companies = objs.map {
            runBlocking { InsightCloudApi.parseInsightObjectToClass(Company2::class.java, it) }
        }
        assertTrue(companies.size == 1)
        val company = companies.first()
        assertTrue(company.id == 1)
        assertTrue(company.name == "Test Gmbh")
        assertTrue(company.country.name == "Germany")
        assertTrue(company.country.shortName == "DE")
    }


}