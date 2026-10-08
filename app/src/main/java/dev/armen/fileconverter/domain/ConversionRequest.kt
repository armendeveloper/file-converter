package dev.armen.fileconverter.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** [path] is a file in app storage (copied from the user's Uri before enqueueing). */
@Serializable
data class ConversionInput(val path: String, val displayName: String)

@Serializable
data class ConversionRequest(
    val inputs: List<ConversionInput>,
    val source: FileFormat,
    val target: FileFormat,
    val options: ConversionOptions = ConversionOptions(),
) {
    fun toJson(): String = RequestJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): ConversionRequest = RequestJson.decodeFromString(serializer(), json)
    }
}

private val RequestJson = Json { ignoreUnknownKeys = true }
