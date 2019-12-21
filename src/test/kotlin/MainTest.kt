import com.coop.technologies.kotlinInsightApi.*
import com.typesafe.config.ConfigFactory
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import com.coop.technologies.kotlinInsightApi.InsightCloudApi
import java.io.File
import java.security.MessageDigest

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
        //val config = ConfigFactory.parseResources("test.conf").resolve()
        //val authToken = config.getString("conf.authToken")
        InsightCloudApi.init(1, "http://localhost:8080", "admin", "admin")
        InsightCloudApi.registerClass(Company::class.java, "Company")
        InsightCloudApi.registerClass(Company2::class.java, "Company")
        InsightCloudApi.registerClass(Country::class.java, "Country")
        InsightCloudApi.registerClass(SimpleCountry::class.java, "Country")
    }

    fun testObjectListWithFlatReference() {
        val companies = runBlocking {
            InsightFactory.findAll<Company>()
        }
        assertTrue(companies.size == 1)
        val company = companies.first()
        assertTrue(company.id == 1)
        assertTrue(company.name == "Test GmbH")
        assertTrue(company.country == "Germany")
    }

    fun testObjectListWithResolvedReference() {
        val companies = runBlocking {
            InsightFactory.findAll<Company2>()
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
            InsightFactory.findById<Company>(1)!!
        }
        assertTrue(company.id == 1)
        assertTrue(company.name == "Test GmbH")
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
            val countryBeforeCreate = InsightFactory.findByName<Country>("England")
            val companyBeforeCreate = InsightFactory.findByName<Company2>("MyTestCompany GmbH")
            assertTrue(countryBeforeCreate == null)
            assertTrue(companyBeforeCreate == null)

            // Create and check direct result
            val country = Country("England", "GB")
            val company = Company2("MyTestCompany GmbH", country)
            company.save()
            assertTrue(company.id > 0)
            assertTrue(company.key.isNotBlank())
            assertTrue(company.country.id > 0)
            assertTrue(company.country.key.isNotBlank())

            // Check England does exists
            val countryAfterCreate = InsightFactory.findByName<Country>("England")
            val companyAfterCreate = InsightFactory.findByName<Company2>("MyTestCompany GmbH")
            assertTrue(countryAfterCreate!!.id == company.country.id)
            assertTrue(countryAfterCreate.key == company.country.key)
            assertTrue(countryAfterCreate.name == company.country.name)
            assertTrue(countryAfterCreate.shortName == company.country.shortName)
            assertTrue(companyAfterCreate!!.id == company.id)
            assertTrue(companyAfterCreate.key == company.key)
            assertTrue(companyAfterCreate.name == company.name)

            // Check Delete
            company.country.delete()
            company.delete()
            val companyAfterDelete = InsightFactory.findById<Company2>(company.id)
            val countryAfterDelete = InsightFactory.findById<Country>(company.country.id)
            assertTrue(companyAfterDelete == null)
            assertTrue(countryAfterDelete == null)
        }
    }

    fun testFilter(){
        runBlocking {
            val countries = InsightFactory.findByIQL<Country>("\"ShortName\"=\"DE\"")
            assertTrue(countries.size == 1)
            assertTrue(countries.first().shortName == "DE")
            assertTrue(countries.first().name == "Germany")
        }
    }

    fun testUpdate(){
        runBlocking {
            val country = InsightFactory.findByName<Country>("Germany")
            assertTrue(country!!.name == "Germany")
            assertTrue(country.shortName == "DE")
            country.shortName = "ED"

            country.save()
            assertTrue(country!!.name == "Germany")
            assertTrue(country.shortName == "ED")

            val countryAfterUpdate = InsightFactory.findByName<Country>("Germany")
            assertTrue(countryAfterUpdate!!.name == "Germany")
            assertTrue(countryAfterUpdate.shortName == "ED")
            country.shortName = "DE"
            country.save()

            val countryAfterReUpdate = InsightFactory.findByName<Country>("Germany")
            assertTrue(countryAfterReUpdate!!.name == "Germany")
            assertTrue(countryAfterReUpdate.shortName == "DE")
        }
    }

    fun testHistory(){
        runBlocking {
            val country = InsightFactory.findByName<Country>("Germany")!!
            val historyItems = country.getHistory()
            assertTrue(historyItems.isNotEmpty())
        }
    }

    fun testAttachments(){
        runBlocking {
            val country = InsightFactory.findByName<Country>("Germany")!!
            val uploadFile = File(MainTest::class.java.getResource("TestAttachment.pdf").file)
            val newAttachment = country.addAttachment(uploadFile.name, uploadFile.readBytes(), "MyComment")
            val attachments = country.getAttachments()
            assertTrue(attachments.size == 1)
            assertTrue(newAttachment.author == attachments.first()!!.author)
            assertTrue(newAttachment.comment == attachments.first().comment)
            assertTrue(newAttachment.filename == attachments.first().filename)
            assertTrue(newAttachment.filesize == attachments.first().filesize)

            val downloadContent = attachments.first().getBytes()
            val md5Hash = MessageDigest.getInstance("MD5").digest(downloadContent).joinToString(""){"%02x".format(it)}
            assertTrue(md5Hash == "3c2f34b03f483bee145a442a4574ca26")

            val deleted = newAttachment.delete()
            val attachmentsAfterDelete = country.getAttachments()
            assertTrue(attachmentsAfterDelete.isEmpty())
        }
    }
}