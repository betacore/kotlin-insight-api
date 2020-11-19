package com.coop.technologies.kotlinInsightApi

import io.ktor.client.HttpClient
import io.ktor.client.engine.jetty.*
import io.ktor.client.features.auth.Auth
import io.ktor.client.features.auth.providers.basic
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import org.eclipse.jetty.util.ssl.SslContextFactory


fun httpClient(user: String, pass: String) =
    HttpClient(Jetty) {
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
            sslContextFactory = SslContextFactory.Client()
        }
    }
