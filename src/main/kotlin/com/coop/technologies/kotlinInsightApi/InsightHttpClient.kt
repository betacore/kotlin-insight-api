package com.coop.technologies.kotlinInsightApi

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.auth.*
import io.ktor.client.features.auth.providers.*
import io.ktor.util.*


@KtorExperimentalAPI
fun httpClient(user: String, pass: String) =
    HttpClient(CIO) {
        install(Auth) {
            basic {
                username = user
                password = pass
                sendWithoutRequest = true
            }
        }
        expectSuccess = false
        engine {

        }
    }
