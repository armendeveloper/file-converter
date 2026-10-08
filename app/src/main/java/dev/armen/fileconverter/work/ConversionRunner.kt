package dev.armen.fileconverter.work

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionRequest
import dev.armen.fileconverter.domain.Engine
import dev.armen.fileconverter.domain.ErrorMapper
import dev.armen.fileconverter.domain.FileNames
import dev.armen.fileconverter.domain.FormatRegistry
import dev.armen.fileconverter.domain.Route
import dev.armen.fileconverter.engine.ConversionTask
import dev.armen.fileconverter.engine.Converter
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

enum class CloudStatus { AVAILABLE, NO_KEY, OFFLINE }

data class RunResult(val route: Route, val outputs: List<File>)

/** Picks the engine for a request and runs it. Android-free, so it is unit-tested on the JVM. */
class ConversionRunner(
    private val sdkInt: Int,
    private val cloudStatus: suspend () -> CloudStatus,
    private val converterFor: suspend (Engine) -> Converter,
) {

    suspend fun run(request: ConversionRequest, inputs: List<File>, outputDir: File, onProgress: (Float) -> Unit): RunResult {
        try {
            val status = cloudStatus()
            val route = FormatRegistry.chooseRoute(request.source, request.target, sdkInt, cloudAvailable = status == CloudStatus.AVAILABLE)
                ?: throw noRouteError(request, status)
            if (inputs.size > 1 && route.engine != Engine.LOCAL_PDF) {
                throw ConversionError.EngineFailed("Only images can be combined into one file.")
            }
            outputDir.mkdirs()
            val task = ConversionTask(
                inputs = inputs,
                source = request.source,
                target = request.target,
                options = request.options,
                outputDir = outputDir,
                baseName = FileNames.baseName(request.inputs.first().displayName),
            )
            val outputs = converterFor(route.engine).convert(task, onProgress)
            if (outputs.isEmpty()) throw ConversionError.EngineFailed("The converter produced no files.")
            return RunResult(route, outputs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ErrorMapper.map(e)
        }
    }

    private fun noRouteError(request: ConversionRequest, status: CloudStatus): ConversionError = when {
        FormatRegistry.routesFor(request.source, request.target, sdkInt).isEmpty() ->
            ConversionError.UnsupportedPair(request.source, request.target)
        status == CloudStatus.NO_KEY -> ConversionError.MissingApiKey()
        else -> ConversionError.NoNetwork()
    }
}
