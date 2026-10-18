package io.github.kurue.bram.app.test

import androidx.test.core.app.ApplicationProvider
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.runtime.llamacpp.ModelProfileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class RecipeProfilePersistenceOnDeviceTest {
    @Test fun provenanceSurvivesStoreRecreationWithoutItsSourceBeingPresent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<BramApplication>()
        val store = ModelProfileStore(app)
        val id = "recipe-persistence-test-${UUID.randomUUID()}"
        val profile = ModelProfile(id, "Test recipe profile", ModelId(id), 4096,
            recipeProvenance = "Test publisher, revision pinned; index SHA-256 ${"a".repeat(64)}; recipe test v1",
            measuredFingerprint = "test-device-driver-engine", autoConfiguredNote = "Validated before save")
        try {
            store.save(profile)
            assertEquals(profile, ModelProfileStore(app).find(id))
        } finally { store.delete(id) }
    }
}
