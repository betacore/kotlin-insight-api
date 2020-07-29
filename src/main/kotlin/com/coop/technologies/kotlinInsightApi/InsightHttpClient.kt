package com.coop.technologies.kotlinInsightApi

import io.ktor.client.HttpClient
import io.ktor.client.features.auth.Auth
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.auth.providers.basic
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature


fun httpClient(user: String, pass: String) =
    HttpClient(Apache) {
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
        install(Auth) {
            basic {
                this.username = user
                this.password = pass
            }
        }
    }
