package io.github.kurue.bram.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoConfigureNoteTest {
    @Test
    fun `winning accelerator note includes literal agreement percentage`() {
        assertEquals(
            "NPU: 123 prompt tok/s, 45 decode tok/s, 95% agreement. Measured 14 Aug 2026.",
            successfulAutoConfigureNote(
                backendLabel = "NPU",
                promptTokensPerSecond = 123.9,
                decodeTokensPerSecond = 45.8,
                agreement = 0.958,
                measuredOn = "14 Aug 2026",
            ),
        )
    }
}
