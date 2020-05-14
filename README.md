# The kotlin Insight Api

## LICENSE
Apache License

In short:

*Do whatever you want with this api, use it personally, use it commercial, copy it, modify it, redistribute it
and you don't have to write my name below your app :)
Hopefully it helps you having fun with developing kotlin and using Jira Insight.*

*__My the force be with you__*

## Import this gradle project as git-source-dependency

__settings.gradle.kts__
```kotlin
    sourceControl {
        gitRepository(uri("https://github.com/betacore/kotlin-insight-api.git")){
            producesModule("com.coop-technologies:kotlin-insight-api")
        }
    }
```
__build.gradle.kts__
```kotlin
    dependencies {
        ...
        // verion = "1.0" stands for the git-tag inside the master branch
        implementation(group = "com.coop-technologies", name = "kotlin-insight-api", version = "1.0")
    }
```

## Description

This *kotlin-insight-api* is a wrapper around the __Jira Insight Plugin__ Rest interface which manages assets.
It shall help to build easy communication between an kotlin app and the plugin data.

You can find full examples [here](https://bitbucket.org/coop-technologies/kotlin-insight-api/src/master/src/test/kotlin/MainTest.kt).


## Initialze the Connection (Singleton)

Before using the api, you have to initialize the service once.

```kotlin
    val schemaId = 1
    val insightAuthToken = "Your-defined-auth-token"
    InsightCloudApi.init(schemaId, insightAuthToken)
```

## Creating the mapping model

Create a regular kotlin class, which matches the insight attributes with first letter lowerCase
for example an insight object Person with attributes Id, Name, Firstname, Lastname

__Simple example__
```kotlin
    // The data class to map the insight object into
    data class MyPersonClass(
        override var name: String,
        var firstname: String,
        var lastname: String
    ) : InsightEntity()

    // register the class to the name of the insight object type
    // InsightCloudApi.registerClass(theKotlinClass, nameOfTheInsightSchemaType)
    InsightCloudApi.registerClass(MyPersonClass::class.java, "Person")
```

__Complex example__
```kotlin
    data class MyPersonClass(
        override var name: String,
        var firstname: String,
        var lastname: String,
        var address: MyAddressClass
    ): InsightEntity()
    
    data class MyAddressClass(
        override var name: String,
        var street: String,
        var number: Int,
        var postalCode: String,
        var country: MyCountryClass // for references in Insight use Int for getting Id, String for getting Name, or use an object class
    ): InsightEntity()
    
    // register classes
    InsightCloudApi.registerClass(MyPersonClass::class.java, "Person")
    InsightCloudApi.registerClass(MyAddressClass::class.java, "Address")
```

## Working with objects

__Find all objects of a registered class__
```kotlin
    val persons = InsightFactory.findAll<Person>()
```

__Find object by id__
```kotlin
    val person = InsightFactory.findById<Person>(53)
```

__Find object by name__
```kotlin
    val person = InsightFactory.findByName<Person>("Insight-Name")
```

__Find object by iql__
```kotlin
    val persons = InsightFactory.findByIQL<Person>("\"Lastname\"=\"MyLastname\"")
```

__Creating objects__
```kotlin
    val myPerson = Person("MyPerson", "MyFirstname", "MyLastName")
    myPerson.save() // if id is not set it will be created, else will be update, save() overrides the id of the object
```

__Updating objects__
```kotlin
    val person = InsightFactory.findById<Person>(1)
    person.firstname = "AnotherFirstname"
    person.save()   // if id is set (by loading or save(), it will be updated, without set id it will be created
```

__Deleting objects__
```kotlin
    val person = InsightFactory.gindById<Person>(1)
    person.delete()   // will only be deleted if id is set
```

## Working with object history

__Get history of an object__
```kotlin
    val person = InsightFactory.findById<Person>(1)
    val history = person.getHistory()
```

## Working with attachements

__Get attachment objects of an object__
```kotlin
    val person = InsightFactory.findByName<Person>("MyPerson")
    val attachments = person.getAttachments()
```

__Download attachment__
```kotlin
    val person = InsightFactory.findByName<Person>("MyPerson")
    val attachments = person.getAttachments()
    val attachment = attachments.first()
    val downloadedBytes = attachment.getBytes() // this starts the download and returns the bytes
```

__Create/Upload attachment__
```kotlin
    val person = InsightFactory.findById<Person>(1)
    val myFile = File("pathToFile")
    person.addAttachment("filename.txt", myFile.readBytes(), "MyComment")
```

__Delete attachment__
```kotlin
    val person = InsightFactory.findByName<Person>("MyPerson")
    val attachments = person.getAttachments()
    val attachment = attachments.first()
    attachment.delete()
```
