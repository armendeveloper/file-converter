package dev.armen.fileconverter.ui.common

import java.util.Locale

object Formatting {
    fun bytes(size: Long?): String {
        if (size == null || size < 0) return ""
        if (size < 1024) return "$size B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = size / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
