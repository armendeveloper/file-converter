package dev.armen.fileconverter.domain

enum class Engine { IMAGE_NATIVE, FFMPEG, LOCAL_PDF, OFFICE_LITE, CLOUD }

/** BASIC routes lose content (e.g. DOCX -> PDF without layout) and are only a fallback. */
enum class Fidelity { FULL, BASIC }

data class Route(val engine: Engine, val fidelity: Fidelity = Fidelity.FULL)
