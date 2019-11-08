import com.coop.technologies.kotlinInsightApi.*
import com.typesafe.config.ConfigFactory
import io.ktor.http.contentRangeHeaderValue
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import org.junit.Test

data class Company(
    override var name: String,
    val country: String
) : InsightEntity()

data class Company2(
    override var name: String,
    val country: Country
) : InsightEntity()

data class Country(
    override var name: String,
    var shortName: String
) : InsightEntity()

data class SimpleCountry(
    override var name: String,
    val shortName: String
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
        InsightCloudApi.registerClass(SimpleCountry::class.java, "Country")
    }

    fun testObjectListWithFlatReference() {
        val companies = runBlocking {
            InsightCloudApi.getObjects(Company::class.java)
        }
        assertTrue(companies.size == 1)
        val company = companies.first()
        assertTrue(company.id == 1)
        assertTrue(company.name == "Test Gmbh")
        assertTrue(company.country == "Germany")
    }

    fun testObjectListWithResolvedReference() {
        val companies = runBlocking {
            InsightCloudApi.getObjects(Company2::class.java)
        }
        assertTrue(companies.size == 1)
        val company = companies.first()
        assertTrue(company.id == 1)
        assertTrue(company.name == "Test Gmbh")
        assertTrue(company.country.name == "Germany")
        assertTrue(company.country.shortName == "DE")
    }

    fun testObjectById() {
        val company = runBlocking {
            InsightCloudApi.getObject(Company::class.java, 1)!!
        }
        assertTrue(company.id == 1)
        assertTrue(company.name == "Test Gmbh")
        assertTrue(company.country == "Germany")
    }

    fun testSchemaLoad() {
        runBlocking {
            InsightCloudApi.reloadSchema()
        }
        val schemas = InsightCloudApi.objectSchemas
    }


    fun testCreateAndDelete() {
        runBlocking {
            // Check England does not exist
            val countryBeforeCreate = InsightCloudApi.getObjectByName(Country::class.java, "England")
            val companyBeforeCreate = InsightCloudApi.getObjectByName(Company2::class.java, "MyTestCompany Gmbh")
            assertTrue(countryBeforeCreate == null)
            assertTrue(companyBeforeCreate == null)

            // Create and check direct result
            val country = Country("England", "GB")
            val company = Company2("MyTestCompany Gmbh", country)
            val createdCompany = InsightCloudApi.createObject(company)
            assertTrue(createdCompany.id > 0)
            assertTrue(createdCompany.key.isNotBlank())
            assertTrue(createdCompany.country.id > 0)
            assertTrue(createdCompany.country.key.isNotBlank())

            // Check England does exists
            val countryAfterCreate = InsightCloudApi.getObjectByName(Country::class.java, "England")
            val companyAfterCreate = InsightCloudApi.getObjectByName(Company2::class.java, "MyTestCompany Gmbh")
            assertTrue(countryAfterCreate!!.id == createdCompany.country.id)
            assertTrue(countryAfterCreate.key == createdCompany.country.key)
            assertTrue(countryAfterCreate.name == createdCompany.country.name)
            assertTrue(countryAfterCreate.shortName == createdCompany.country.shortName)
            assertTrue(companyAfterCreate!!.id == createdCompany.id)
            assertTrue(companyAfterCreate.key == createdCompany.key)
            assertTrue(companyAfterCreate.name == createdCompany.name)

            // Check Delete
            InsightCloudApi.deleteObject(createdCompany.id)
            InsightCloudApi.deleteObject(createdCompany.country.id)
            val companyAfterDelete = InsightCloudApi.getObject(Company2::class.java, createdCompany.id)
            val countryAfterDelete = InsightCloudApi.getObject(Country::class.java, createdCompany.country.id)
            assertTrue(companyAfterDelete == null)
            assertTrue(countryAfterDelete == null)
        }
    }

    fun testFilter(){
        runBlocking {
            val countries = InsightCloudApi.getObjectByIQL(Country::class.java, "\"ShortName\"=\"DE\"")
            assertTrue(countries.size == 1)
            assertTrue(countries.first().shortName == "DE")
            assertTrue(countries.first().name == "Germany")
        }
    }

    fun testUpdate(){
        runBlocking {
            val country = InsightCloudApi.getObjectByName(Country::class.java, "Germany")
            assertTrue(country!!.name == "Germany")
            assertTrue(country.shortName == "DE")
            country.shortName = "ED"

            val newCountry = InsightCloudApi.updateObject(country)
            assertTrue(newCountry!!.name == "Germany")
            assertTrue(newCountry.shortName == "ED")

            val countryAfterUpdate = InsightCloudApi.getObjectByName(Country::class.java, "Germany")
            assertTrue(countryAfterUpdate!!.name == "Germany")
            assertTrue(countryAfterUpdate.shortName == "ED")
            country.shortName = "DE"
            InsightCloudApi.updateObject(country)

            val countryAfterReUpdate = InsightCloudApi.getObjectByName(Country::class.java, "Germany")
            assertTrue(countryAfterReUpdate!!.name == "Germany")
            assertTrue(countryAfterReUpdate.shortName == "DE")
        }
    }

    fun testHistory(){
        runBlocking {
            val country = InsightCloudApi.getObjectByName(Country::class.java, "Germany")!!
            val historyItems = InsightCloudApi.getHistory(country)
            assertTrue(historyItems.isNotEmpty())
        }
    }
}