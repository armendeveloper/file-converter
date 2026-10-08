package dev.armen.fileconverter.work

import kotlinx.coroutines.sync.Mutex

/** Serialises conversions process-wide: FFmpeg and large PDFs should never run in parallel on a phone. */
object ConversionLock {
    val mutex = Mutex()
}
