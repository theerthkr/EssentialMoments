package com.theerthkr.essentialmoments

import com.theerthkr.essentialmoments.ml.InferenceArbiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class InferenceArbiterTest {

    @Test
    fun testHighPriorityRunsImmediatelyWhenIdle() {
        val arbiter = InferenceArbiter()
        val result = arbiter.runHighPriority { "high" }
        assertEquals("high", result)
    }

    @Test
    fun testLowPriorityRunsWhenNoHighPriorityPending() {
        val arbiter = InferenceArbiter()
        val result = arbiter.runLowPriority { "low" }
        assertEquals("low", result)
    }

    @Test
    fun testHighPriorityPreemptsLowPriorityQueue() {
        val arbiter = InferenceArbiter()
        val lowStarted = CountDownLatch(1)
        val highFinished = CountDownLatch(1)
        val lowFinishedAfterHigh = AtomicBoolean(false)

        val threadLow = Thread {
            arbiter.runLowPriority {
                lowStarted.countDown()
                // Simulate time spent on one image
                Thread.sleep(100)
            }
            // Second low priority call should be blocked if high priority arrives
            arbiter.runLowPriority {
                lowFinishedAfterHigh.set(true)
            }
        }

        threadLow.start()
        assertTrue(lowStarted.await(1, TimeUnit.SECONDS))

        // High priority search arrives
        val highResult = arbiter.runHighPriority {
            "searchResult"
        }
        highFinished.countDown()

        threadLow.join(2000)
        assertEquals("searchResult", highResult)
        assertTrue(lowFinishedAfterHigh.get())
    }
}
