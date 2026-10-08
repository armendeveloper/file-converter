package dev.armen.fileconverter.domain

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Turns any throwable into a [ConversionError]. Callers must rethrow CancellationException themselves. */
object ErrorMapper {
    fun map(t: Throwable): ConversionError = when (t) {
        is ConversionError -> t
        is UnknownHostException, is ConnectException, is SocketTimeoutException, is NoRouteToHostException ->
            ConversionError.NoNetwork(t)
        is IOException -> if (isNoSpace(t)) ConversionError.InsufficientStorage(t) else ConversionError.EngineFailed(t.message, t)
        is OutOfMemoryError -> ConversionError.EngineFailed("Out of memory: the file is too large to process on this device.", t)
        else -> ConversionError.EngineFailed(t.message ?: t::class.java.simpleName, t)
    }

    private fun isNoSpace(t: Throwable): Boolean {
        val messages = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return "ENOSPC" in messages || "No space left" in messages
    }
}
