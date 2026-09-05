package com.debritsu.app.data

import kotlinx.serialization.json.*

val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

fun JsonElement?.obj(key: String): JsonObject? =
    (this as? JsonObject)?.get(key) as? JsonObject

fun JsonElement?.arr(key: String): JsonArray? =
    (this as? JsonObject)?.get(key) as? JsonArray

fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

fun JsonElement?.int(key: String): Int? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()
