package dev.armen.fileconverter.engine.cloud

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ErrorMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thin CloudConvert REST v2 client. Every call runs on Dispatchers.IO and is cancellable. */
class CloudConvertClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: HttpUrl = PRODUCTION_URL,
) {

    suspend fun createJob(inputFormat: String, outputFormat: String): JobDto {
        val body = buildJsonObject {
            putJsonObject("tasks") {
                putJsonObject("upload-file") { put("operation", "import/upload") }
                putJsonObject("convert-file") {
                    put("operation", "convert")
                    put("input", "upload-file")
                    put("input_format", inputFormat)
                    put("output_format", outputFormat)
                }
                putJsonObject("export-file") {
                    put("operation", "export/url")
                    put("input", "convert-file")
                }
            }
        }
        val request = authorized("jobs").post(body.toString().toRequestBody(JSON_MEDIA)).build()
        return json.decodeFromString(JobEnvelope.serializer(), execute(request)).data
    }

    suspend fun getJob(id: String): JobDto =
        json.decodeFromString(JobEnvelope.serializer(), execute(authorized("jobs/$id").get().build())).data

    suspend fun deleteJob(id: String) {
        execute(authorized("jobs/$id").delete().build())
    }

    suspend fun me(): UserDto =
        json.decodeFromString(UserEnvelope.serializer(), execute(authorized("users/me").get().build())).data

    /** Posts the file to the signed upload form. The form URL is pre-signed, so no Authorization header. */
    suspend fun upload(form: UploadFormDto, file: File, fileName: String, onProgress: (Float) -> Unit) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            form.parameters.forEach { (key, value) -> addFormDataPart(key, (value as? JsonPrimitive)?.content ?: value.toString()) }
            addFormDataPart("file", fileName, ProgressRequestBody(file.asRequestBody(OCTET_STREAM), onProgress))
        }.build()
        val request = Request.Builder().url(form.url).post(body).build()
        withContext(Dispatchers.IO) {
            call(request).use { response ->
                if (!response.isSuccessful) throw mapHttpError(response.code, response.body?.string())
            }
        }
    }

    suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            call(Request.Builder().url(url).get().build()).use { response ->
                if (!response.isSuccessful) throw mapHttpError(response.code, response.body?.string())
                val body = response.body ?: throw ConversionError.EngineFailed("CloudConvert sent an empty file.")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        onProgress(1f)
    }

    private fun authorized(path: String): Request.Builder =
        Request.Builder()
            .url(baseUrl.resolve(path) ?: throw IllegalArgumentException("Bad path $path"))
            .header("Authorization", "Bearer $apiKey")

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        call(request).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapHttpError(response.code, body)
            body
        }
    }

    private suspend fun call(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(ErrorMapper.map(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        val PRODUCTION_URL: HttpUrl = "https://api.cloudconvert.com/v2/".toHttpUrl()
        val SANDBOX_URL: HttpUrl = "https://api.sandbox.cloudconvert.com/v2/".toHttpUrl()
        private val JSON_MEDIA = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        internal fun mapHttpError(code: Int, body: String?): ConversionError {
            val message = body?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString(ErrorDto.serializer(), it).message }.getOrNull() }
                ?: "HTTP $code"
            return when (code) {
                401, 403 -> ConversionError.InvalidApiKey()
                402, 429 -> ConversionError.QuotaExceeded()
                else -> ConversionError.EngineFailed("CloudConvert: $message")
            }
        }
    }
}
