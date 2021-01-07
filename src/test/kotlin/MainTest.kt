import com.coop.technologies.kotlinInsightApi.ExecutionEnvironment
import com.coop.technologies.kotlinInsightApi.InsightEntity
import com.coop.technologies.kotlinInsightApi.InsightFactory
import com.coop.technologies.kotlinInsightApi.buildEnvironment
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import java.io.File
import java.security.MessageDigest

data class Company(
    override var name: String,
    val country: InsightEntity
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

data class TestWithListsWithSimple(
    override var name: String,
    val itemList: List<InsightEntity>
) : InsightEntity()

data class TestWithListsObject(
    override var name: String,
    val itemList: List<SimpleObject>
) : InsightEntity()

data class SimpleObject(
    override var name: String,
    val firstname: String,
    val lastname: String
) : InsightEntity()

@Ignore
class MainTest : TestCase() {
    lateinit var environment: ExecutionEnvironment

    override fun setUp() {
        super.setUp()
        println("#### Starting setUp")
        //val config = ConfigFactory.parseResources("test.conf").resolve()
        //val authToken = config.getString("conf.authToken")
        val mapping = mapOf(
            Company::class to "Company",
            Company2::class to "Company",
            Country::class to "Country",
            SimpleCountry::class to "Country",
            TestWithListsWithSimple::class to "TestWithLists",
            TestWithListsObject::class to "TestWithLists",
            SimpleObject::class to "SimpleObject"
        )
        environment = runBlocking {
            buildEnvironment(1, "http://localhost:8080", "admin", "admin", mapping)
        }
    }

    fun testObjectListWithFlatReference() {
        val companies = runBlocking {
            InsightFactory.findAll<Company>(environment)
        }
        assertTrue(companies.size == 1)
        val company = companies.first()
        assertTrue(company.id == 1)
        assertTrue(company.name == "Test GmbH")
        assertTrue(company.country.name == "Germany")
    }

    fun testObjectListWithResolvedReference() {
        val companies = runBlocking {
            InsightFactory.findAll<Company2>(environment)
        }
        assertTrue(companies.size == 1)
        val company = companies.first()
        assertTrue(company.id == 1)
        assertTrue(company.name == "Test GmbH")
        assertTrue(company.country.name == "Germany")
        assertTrue(company.country.shortName == "DE")
    }

    fun testObjectById() {
        val company = runBlocking {
            InsightFactory.findById<Company>(1, environment)!!
        }
        assertTrue(company.id == 1)
        assertTrue(company.name == "Test GmbH")
        assertTrue(company.country.name == "Germany")
    }

    fun testObjectWithListAttributes() {
        val withObject = runBlocking { InsightFactory.findAll<TestWithListsObject>(environment) }.first()
        assertTrue(withObject.itemList.map { it.firstname } == listOf("F1", "F2", "F3"))
    }

    // Todo: Implement simple object resolution for list object references
    @Ignore
    fun testNamesWithListAttributes() {
        val withStrings = runBlocking { InsightFactory.findAll<TestWithListsWithSimple>(environment) }.first()
        assertTrue(withStrings.itemList.map { it.name } == listOf("Object1", "Object2", "Object3"))
    }

    fun testCreateAndDelete() {
        runBlocking {
            // Check England does not exist
            val countryBeforeCreate = InsightFactory.findByName<Country>("England", environment)
            val companyBeforeCreate = InsightFactory.findByName<Company2>("MyTestCompany GmbH", environment)
            assertTrue(countryBeforeCreate == null)
            assertTrue(companyBeforeCreate == null)

            // Create and check direct result
            val country = Country("England", "GB")
            val company = Company2("MyTestCompany GmbH", country)
            company.save(environment)
            assertTrue(company.id > 0)
            assertTrue(company.key.isNotBlank())
            assertTrue(company.country.id > 0)
            assertTrue(company.country.key.isNotBlank())

            // Check England does exists
            val countryAfterCreate = InsightFactory.findByName<Country>("England", environment)
            val companyAfterCreate = InsightFactory.findByName<Company2>("MyTestCompany GmbH", environment)
            assertTrue(countryAfterCreate!!.id == company.country.id)
            assertTrue(countryAfterCreate.key == company.country.key)
            assertTrue(countryAfterCreate.name == company.country.name)
            assertTrue(countryAfterCreate.shortName == company.country.shortName)
            assertTrue(companyAfterCreate!!.id == company.id)
            assertTrue(companyAfterCreate.key == company.key)
            assertTrue(companyAfterCreate.name == company.name)

            // Check Delete
            company.country.delete(environment)
            company.delete(environment)
            val companyAfterDelete = InsightFactory.findById<Company2>(company.id, environment)
            val countryAfterDelete = InsightFactory.findById<Country>(company.country.id, environment)
            assertTrue(companyAfterDelete == null)
            assertTrue(countryAfterDelete == null)
        }
    }

    fun testFilter() {
        runBlocking {
            val countries = InsightFactory.findByIQL<Country>("\"ShortName\"=\"DE\"", environment)
            assertTrue(countries.size == 1)
            assertTrue(countries.first().shortName == "DE")
            assertTrue(countries.first().name == "Germany")
        }
    }

    fun testUpdate() {
        runBlocking {
            val country = InsightFactory.findByName<Country>("Germany", environment)
            assertTrue(country!!.name == "Germany")
            assertTrue(country.shortName == "DE")
            country.shortName = "ED"

            country.save(environment)
            assertTrue(country!!.name == "Germany")
            assertTrue(country.shortName == "ED")

            val countryAfterUpdate = InsightFactory.findByName<Country>("Germany", environment)
            assertTrue(countryAfterUpdate!!.name == "Germany")
            assertTrue(countryAfterUpdate.shortName == "ED")
            country.shortName = "DE"
            country.save(environment)

            val countryAfterReUpdate = InsightFactory.findByName<Country>("Germany", environment)
            assertTrue(countryAfterReUpdate!!.name == "Germany")
            assertTrue(countryAfterReUpdate.shortName == "DE")
        }
    }

    fun testHistory() {
        runBlocking {
            val country = InsightFactory.findByName<Country>("Germany", environment)!!
            val historyItems = country.getHistory(environment)
            assertTrue(historyItems.isNotEmpty())
        }
    }

    fun testAttachments() {
        runBlocking {
            val country = InsightFactory.findByName<Country>("Germany", environment)!!
            val uploadFile = File(MainTest::class.java.getResource("TestAttachment.pdf").file)
            val newAttachment = country.addAttachment(uploadFile.name, uploadFile.readBytes(), "MyComment", environment)
            val attachments = country.getAttachments(environment)
            assertTrue(attachments.size == 1)
            assertTrue(newAttachment.author == attachments.first()!!.author)
            assertTrue(newAttachment.comment == attachments.first().comment)
            assertTrue(newAttachment.filename == attachments.first().filename)
            assertTrue(newAttachment.filesize == attachments.first().filesize)

            val downloadContent = attachments.first().getBytes(environment)
            val md5Hash =
                MessageDigest.getInstance("MD5").digest(downloadContent).joinToString("") { "%02x".format(it) }
            assertTrue(md5Hash == "3c2f34b03f483bee145a442a4574ca26")

            val deleted = newAttachment.delete(environment)
            val attachmentsAfterDelete = country.getAttachments(environment)
            assertTrue(attachmentsAfterDelete.isEmpty())
        }
    }
}