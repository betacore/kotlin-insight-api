package com.coop.technologies.kotlinInsightApi

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.features.CallLogging


fun httpClient() =
    HttpClient(Apache) {
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
    }
