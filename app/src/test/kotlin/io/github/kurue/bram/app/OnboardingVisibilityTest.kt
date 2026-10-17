package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.LiteRtModelRecord
import io.github.kurue.bram.core.domain.ModelId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingVisibilityTest {

    private val liteRt = LiteRtModelRecord(
        id = ModelId("litert-1"),
        displayName = "Gemma",
        fileName = "gemma.litertlm",
        contentUri = "content://x",
        localPath = "/data/x",
        fileSizeBytes = 1,
        sha256 = "00",
    )

    @Test
    fun `a fresh install shows the guide only once the empty catalogs are known`() {
        val fresh = AppUiState(onboardingCompleted = false)
        assertFalse("must not flash before the catalogs load", fresh.showOnboarding)
        assertTrue(fresh.copy(catalogsLoaded = true).showOnboarding)
    }

    @Test
    fun `any model or server hides the guide, including a LiteRT package`() {
        val state = AppUiState(onboardingCompleted = false, catalogsLoaded = true, litertlmModels = listOf(liteRt))
        assertTrue(state.hasAnyModel)
        assertFalse(state.showOnboarding)
    }

    @Test
    fun `a finished guide stays closed with no models, until reopened from Settings`() {
        val done = AppUiState(onboardingCompleted = true, catalogsLoaded = true)
        assertFalse(done.showOnboarding)
        assertTrue(done.copy(onboardingReopened = true, litertlmModels = listOf(liteRt)).showOnboarding)
    }
}
