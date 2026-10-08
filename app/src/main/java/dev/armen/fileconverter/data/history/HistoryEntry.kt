package dev.armen.fileconverter.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.armen.fileconverter.domain.FileFormat

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val sourceName: String,
    val sourceFormat: FileFormat,
    val targetFormat: FileFormat,
    val outputUri: String,
    val outputName: String,
    val outputSizeBytes: Long,
)
