package io.github.kurue.bram.app.test

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.core.domain.SkillActionOutcome
import io.github.kurue.bram.core.domain.SkillOrigin
import io.github.kurue.bram.platform.android.PersistentSkillStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The skills file's format history, against the real store: a version-1 bare array still reads
 * (with its versions treated as user-approved, since that is how they got there), and anything
 * unreadable is quarantined aside instead of taking every skill interaction down with it.
 */
@RunWith(AndroidJUnit4::class)
class SkillStoreMigrationOnDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<BramApplication>()
    private val file: File get() = File(context.filesDir, "skills.json")

    private fun quarantineFiles() =
        context.filesDir.listFiles { entry -> entry.name.startsWith("skills.json.corrupt-") }.orEmpty()

    @Test
    fun aVersionOneFileIsReadAndUpgradedOnTheNextWrite() = runBlocking {
        val backup = file.takeIf(File::isFile)?.readText()
        try {
            file.writeText(
                """[{"id":"legacy","name":"Legacy","activeVersion":"1.0.0","draftVersion":null,""" +
                    """"updatedAtEpochMillis":1,"versions":[{"version":"1.0.0","description":"d",""" +
                    """"instructions":"i","importedAtEpochMillis":1}]}]""",
            )

            val store = PersistentSkillStore(context)
            val pkg = store.packages().first { it.id == "legacy" }
            assertEquals("1.0.0", pkg.activeVersion)
            val version = pkg.versions.single()
            assertEquals("version-1 data was imported by the user", SkillOrigin.USER, version.origin)
            assertTrue("a legacy version counts as approved", version.approvedAtEpochMillis != null)

            assertEquals(SkillActionOutcome.Ok, store.disable("legacy"))
            val rewritten = file.readText()
            assertTrue("the file is rewritten in the versioned shape", rewritten.contains("\"schemaVersion\":2"))
            assertTrue("the legacy skill survives the upgrade", rewritten.contains("legacy"))
        } finally {
            if (backup != null) file.writeText(backup) else file.delete()
        }
    }

    @Test
    fun aCorruptFileIsQuarantinedAndTheStoreStartsEmpty() = runBlocking {
        val backup = file.takeIf(File::isFile)?.readText()
        val quarantinesBefore = quarantineFiles().map { it.name }.toSet()
        try {
            file.writeText("{not json at all")

            val store = PersistentSkillStore(context)

            assertEquals("a broken file reads as no skills", emptyList<String>(), store.packages().map { it.id })
            val quarantined = quarantineFiles().map { it.name }.toSet() - quarantinesBefore
            assertEquals("the broken file is moved aside, not deleted silently", 1, quarantined.size)
        } finally {
            quarantineFiles().filter { it.name !in quarantinesBefore }.forEach { it.delete() }
            if (backup != null) file.writeText(backup) else file.delete()
        }
    }
}
