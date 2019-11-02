package com.linktime.myHotel.insight

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature


fun httpClient() =
    HttpClient(Apache) {
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
    }
