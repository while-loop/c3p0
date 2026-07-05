package dev.whileloop.c3p0.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.whileloop.c3p0.data.model.CachedWeightHistoryRecord
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeightHistoryCacheRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val cacheFile: File
        get() = File(context.noBackupFilesDir, CACHE_FILE_NAME)

    private val metadataFile: File
        get() = File(context.noBackupFilesDir, METADATA_FILE_NAME)

    suspend fun readWeightHistory(): List<CachedWeightHistoryRecord> =
        runCatching {
            cacheFile
                .takeIf { it.exists() }
                ?.readLines()
                ?.mapNotNull { line -> line.toCachedWeightHistoryRecordOrNull() }
                .orEmpty()
                .sortedBy { it.time }
        }.getOrDefault(emptyList())

    suspend fun readLastFetchTime(): Instant? =
        runCatching {
            metadataFile
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it) }
        }.getOrNull()

    suspend fun saveWeightHistory(records: List<CachedWeightHistoryRecord>, fetchedThroughTime: Instant) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(
                records
                    .sortedBy { it.time }
                    .joinToString(separator = "\n") { record ->
                        listOf(
                            record.time,
                            record.weightKg
                        ).joinToString(separator = FIELD_SEPARATOR)
                    }
            )
            metadataFile.writeText(fetchedThroughTime.toString())
        }
    }

    private fun String.toCachedWeightHistoryRecordOrNull(): CachedWeightHistoryRecord? {
        val fields = split(FIELD_SEPARATOR)
        if (fields.size != CACHE_FIELD_COUNT) return null
        return runCatching {
            CachedWeightHistoryRecord(
                time = Instant.parse(fields[0]),
                weightKg = fields[1].toDouble()
            )
        }.getOrNull()
    }

    private companion object {
        private const val CACHE_FILE_NAME = "weight_history_cache.tsv"
        private const val METADATA_FILE_NAME = "weight_history_last_fetch.txt"
        private const val FIELD_SEPARATOR = "\t"
        private const val CACHE_FIELD_COUNT = 2
    }
}
