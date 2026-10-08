package dev.armen.fileconverter.engine.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class JobEnvelope(val data: JobDto)

@Serializable
data class JobDto(val id: String, val status: String, val tasks: List<TaskDto> = emptyList())

@Serializable
data class TaskDto(
    val id: String,
    val name: String? = null,
    val operation: String,
    val status: String,
    val code: String? = null,
    val message: String? = null,
    val result: TaskResultDto? = null,
)

@Serializable
data class TaskResultDto(val form: UploadFormDto? = null, val files: List<ExportFileDto>? = null)

/** Parameters must be sent back verbatim with the upload; values may be strings or numbers. */
@Serializable
data class UploadFormDto(val url: String, val parameters: Map<String, JsonElement> = emptyMap())

@Serializable
data class ExportFileDto(val filename: String, val url: String)

@Serializable
data class UserEnvelope(val data: UserDto)

@Serializable
data class UserDto(val username: String? = null, val email: String? = null, val credits: Int? = null)

@Serializable
data class ErrorDto(val message: String? = null, val code: String? = null)
