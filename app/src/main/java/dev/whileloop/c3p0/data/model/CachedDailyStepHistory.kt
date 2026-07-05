package dev.whileloop.c3p0.data.model

import java.time.LocalDate

data class CachedDailyStepHistory(
    val date: LocalDate,
    val rawSteps: Long,
    val normalizedSteps: Long,
    val c3p0Steps: Long,
    val excludedOtherSessionSteps: Long
)
