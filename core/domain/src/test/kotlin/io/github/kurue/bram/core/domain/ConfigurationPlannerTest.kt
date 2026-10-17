package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationPlannerTest {

    @Test
    fun `chat tunes threads and batch first and the cpu mask last`() {
        val chat = ConfigurationPlanner.dimensions(ConfigWorkload.CHAT, npu = false)
        assertEquals(TuningDimension.THREADS, chat.first())
        assertEquals(TuningDimension.BATCH, chat[1])
        assertEquals(TuningDimension.CPU_MASK, chat.last())
        assertFalse("chat does not pay for padded KV measurements", TuningDimension.KV_CACHE in chat)
        assertFalse(TuningDimension.HEX_FLAGS in chat)
    }

    @Test
    fun `long context adds the KV cache and attention, and the NPU adds its flags`() {
        val long = ConfigurationPlanner.dimensions(ConfigWorkload.LONG_CONTEXT, npu = true)
        assertTrue(long.indexOf(TuningDimension.KV_CACHE) in 1..2)
        assertTrue(TuningDimension.FLASH_ATTENTION in long)
        assertTrue(TuningDimension.HEX_FLAGS in long)
        assertEquals(long.size, long.toSet().size)
    }

    @Test
    fun `a difference inside the noise keeps the current setting`() {
        val scores = listOf(TrialScore("512/128", 1000), TrialScore("256/128", 970), TrialScore("1024/128", 990))
        assertEquals("512/128", ConfigurationPlanner.pickWinner(scores, incumbentLabel = "512/128")?.label)
    }

    @Test
    fun `a clear improvement replaces the current setting`() {
        val scores = listOf(TrialScore("4 threads", 1000), TrialScore("6 threads", 800))
        assertEquals("6 threads", ConfigurationPlanner.pickWinner(scores, incumbentLabel = "4 threads")?.label)
    }

    @Test
    fun `without a measured incumbent the fastest wins, and nothing scored is nothing`() {
        val scores = listOf(TrialScore("a", 1000), TrialScore("b", 990))
        assertEquals("b", ConfigurationPlanner.pickWinner(scores, incumbentLabel = "timed-out default")?.label)
        assertNull(ConfigurationPlanner.pickWinner(emptyList(), "a"))
    }

    @Test
    fun `the budget refuses a trial it cannot finish`() {
        var now = 0L
        val budget = ConfigurationBudget(totalMillis = 120_000, now = { now })
        assertTrue(budget.affords(60_000))
        now = 100_000
        assertEquals(20_000, budget.remainingMillis)
        assertFalse(budget.affords(30_000))
        now = 200_000
        assertEquals(0, budget.remainingMillis)
    }

    @Test
    fun `trial cost is estimated conservatively from recent trials`() {
        val cost = TrialCostEstimate(window = 3)
        assertEquals(9_000, cost.estimate(fallbackMillis = 9_000))
        listOf(4_000L, 12_000L, 5_000L, 6_000L).forEach(cost::observe)
        assertEquals("the slowest of the last three", 12_000, cost.estimate(0))
        cost.observe(5_500)
        assertEquals(6_000, cost.estimate(0))
    }
}
