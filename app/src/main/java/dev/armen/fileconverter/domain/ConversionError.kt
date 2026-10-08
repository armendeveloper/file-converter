package dev.armen.fileconverter.domain

enum class ErrorKind {
    UNSUPPORTED_PAIR, UNREADABLE_INPUT, MISSING_API_KEY, INVALID_API_KEY,
    QUOTA_EXCEEDED, NO_NETWORK, INSUFFICIENT_STORAGE, ENGINE_FAILED,
}

/** Every failure shown to the user. [message] is the user-facing text from spec §6. */
sealed class ConversionError(
    val kind: ErrorKind,
    message: String,
    val details: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class UnsupportedPair(source: FileFormat, target: FileFormat) :
        ConversionError(ErrorKind.UNSUPPORTED_PAIR, "Can't convert ${source.label} to ${target.label}.")

    class UnreadableInput(format: FileFormat?, cause: Throwable? = null) : ConversionError(
        ErrorKind.UNREADABLE_INPUT,
        "This file looks damaged or isn't really a ${format?.label ?: "supported"} file.",
        cause?.message,
        cause,
    )

    class MissingApiKey : ConversionError(ErrorKind.MISSING_API_KEY, "Add your CloudConvert API key in Settings.")

    class InvalidApiKey : ConversionError(ErrorKind.INVALID_API_KEY, "Your CloudConvert key was rejected. Check it in Settings.")

    class QuotaExceeded : ConversionError(ErrorKind.QUOTA_EXCEEDED, "CloudConvert limit reached — try later or upgrade your plan.")

    class NoNetwork(cause: Throwable? = null) :
        ConversionError(ErrorKind.NO_NETWORK, "No internet connection.", cause?.message, cause)

    class InsufficientStorage(cause: Throwable? = null) :
        ConversionError(ErrorKind.INSUFFICIENT_STORAGE, "Not enough storage space.", cause?.message, cause)

    class EngineFailed(details: String?, cause: Throwable? = null) :
        ConversionError(ErrorKind.ENGINE_FAILED, "Conversion failed.", details, cause)
}
