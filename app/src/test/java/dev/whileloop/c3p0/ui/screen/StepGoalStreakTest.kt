package dev.whileloop.c3p0.ui.screen

import dev.whileloop.c3p0.domain.usecase.DailyStepHistory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StepGoalStreakTest {
    private val today = LocalDate.of(2026, 7, 22)

    @Test
    fun countsConsecutiveGoalDaysThroughToday() {
        val rows = listOf(
            stepsToday(0, 12_000),
            stepsToday(1, 10_000),
            stepsToday(2, 11_500),
            stepsToday(3, 9_999)
        )

        assertEquals(3, calculateStepGoalStreak(rows, stepGoal = 10_000, today = today))
    }

    @Test
    fun unfinishedTodayDoesNotBreakYesterdayStreak() {
        val rows = listOf(
            stepsToday(0, 4_000),
            stepsToday(1, 10_000),
            stepsToday(2, 12_000),
            stepsToday(3, 8_000)
        )

        assertEquals(2, calculateStepGoalStreak(rows, stepGoal = 10_000, today = today))
    }

    @Test
    fun missingDayBreaksStreak() {
        val rows = listOf(
            stepsToday(0, 10_000),
            stepsToday(2, 10_000)
        )

        assertEquals(1, calculateStepGoalStreak(rows, stepGoal = 10_000, today = today))
    }

    @Test
    fun missedYesterdayLeavesNoCurrentStreak() {
        val rows = listOf(
            stepsToday(0, 2_000),
            stepsToday(1, 9_999),
            stepsToday(2, 15_000)
        )

        assertEquals(0, calculateStepGoalStreak(rows, stepGoal = 10_000, today = today))
    }

    @Test
    fun nonPositiveGoalDoesNotCreateAnAutomaticStreak() {
        assertEquals(0, calculateStepGoalStreak(emptyList(), stepGoal = 0, today = today))
    }

    private fun stepsToday(daysAgo: Long, steps: Long) =
        DailyStepHistory(date = today.minusDays(daysAgo), steps = steps)
}
