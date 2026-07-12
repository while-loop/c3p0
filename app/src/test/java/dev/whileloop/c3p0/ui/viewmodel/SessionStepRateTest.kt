package dev.whileloop.c3p0.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStepRateTest {
    @Test
    fun rateUsesSameTrailingThreeMinuteWindowAsGoalEta() {
        val rate = calculateRecentStepsPerMinute(
            listOf(
                StepRateSample(elapsedSeconds = 0, sessionSteps = 0),
                StepRateSample(elapsedSeconds = 60, sessionSteps = 80),
                StepRateSample(elapsedSeconds = 240, sessionSteps = 350)
            )
        )

        assertEquals(90f, rate!!, 0.001f)
    }

    @Test
    fun rateWaitsForEnoughRecentTimeAndSteps() {
        assertNull(
            calculateRecentStepsPerMinute(
                listOf(
                    StepRateSample(elapsedSeconds = 0, sessionSteps = 0),
                    StepRateSample(elapsedSeconds = 29, sessionSteps = 20)
                )
            )
        )
        assertNull(
            calculateRecentStepsPerMinute(
                listOf(
                    StepRateSample(elapsedSeconds = 0, sessionSteps = 0),
                    StepRateSample(elapsedSeconds = 60, sessionSteps = 9)
                )
            )
        )
    }
}
