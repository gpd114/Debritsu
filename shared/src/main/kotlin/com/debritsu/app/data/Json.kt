package com.debritsu.app.data

import kotlinx.serialization.json.*

val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

fun JsonElement?.obj(key: String): JsonObject? =
    (this as? JsonObject)?.get(key) as? JsonObject

fun JsonElement?.arr(key: String): JsonArray? =
    (this as? JsonObject)?.get(key) as? JsonArray

fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

/** Sizes in bytes overflow an Int at 2 GB, which is a common episode. */
fun JsonElement?.long(key: String): Long? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()

fun JsonElement?.int(key: String): Int? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()
