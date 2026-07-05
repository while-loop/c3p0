package dev.whileloop.c3p0.data.model

import java.time.Instant

data class CachedWeightHistoryRecord(
    val time: Instant,
    val weightKg: Double
)
