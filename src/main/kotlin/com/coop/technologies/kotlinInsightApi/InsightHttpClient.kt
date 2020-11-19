package com.coop.technologies.kotlinInsightApi

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.*
import io.ktor.client.features.auth.Auth
import io.ktor.client.features.auth.providers.basic
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.util.*


@KtorExperimentalAPI
fun httpClient(user: String, pass: String) =
    HttpClient(CIO) {
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
        install(Auth) {
            basic {
                username = user
                password = pass
                sendWithoutRequest = true
            }
        }
        engine {
        }
    }
