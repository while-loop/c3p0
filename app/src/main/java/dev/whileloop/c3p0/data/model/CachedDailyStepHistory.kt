package dev.whileloop.c3p0.data.model

import java.time.LocalDate

data class CachedDailyStepHistory(
    val date: LocalDate,
    val steps: Long
)
