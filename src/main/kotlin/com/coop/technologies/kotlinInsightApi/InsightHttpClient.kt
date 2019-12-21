package com.coop.technologies.kotlinInsightApi

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.auth.basic.BasicAuth


fun httpClient(username: String, password: String) =
    HttpClient(Apache) {
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
        install(BasicAuth) {
            this.username = username
            this.password = password
        }
    }
