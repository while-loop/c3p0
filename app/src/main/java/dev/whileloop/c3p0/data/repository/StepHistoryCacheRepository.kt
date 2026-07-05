package dev.whileloop.c3p0.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.whileloop.c3p0.data.model.CachedDailyStepHistory
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StepHistoryCacheRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val cacheFile: File
        get() = File(context.noBackupFilesDir, CACHE_FILE_NAME)

    suspend fun readDailyStepHistory(): List<CachedDailyStepHistory> =
        runCatching {
            cacheFile
                .takeIf { it.exists() }
                ?.readLines()
                ?.mapNotNull { line -> line.toCachedDailyStepHistoryOrNull() }
                .orEmpty()
        }.getOrDefault(emptyList())

    suspend fun saveDailyStepHistory(rows: List<CachedDailyStepHistory>) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(
                rows.joinToString(separator = "\n") { row ->
                    listOf(
                        row.date,
                        row.rawSteps,
                        row.normalizedSteps,
                        row.c3p0Steps,
                        row.excludedOtherSessionSteps
                    ).joinToString(separator = FIELD_SEPARATOR)
                }
            )
        }
    }

    private fun String.toCachedDailyStepHistoryOrNull(): CachedDailyStepHistory? {
        val fields = split(FIELD_SEPARATOR)
        if (fields.size != CACHE_FIELD_COUNT) return null
        return runCatching {
            CachedDailyStepHistory(
                date = LocalDate.parse(fields[0]),
                rawSteps = fields[1].toLong(),
                normalizedSteps = fields[2].toLong(),
                c3p0Steps = fields[3].toLong(),
                excludedOtherSessionSteps = fields[4].toLong()
            )
        }.getOrNull()
    }

    private companion object {
        private const val CACHE_FILE_NAME = "daily_step_history_cache.tsv"
        private const val FIELD_SEPARATOR = "\t"
        private const val CACHE_FIELD_COUNT = 5
    }
}
