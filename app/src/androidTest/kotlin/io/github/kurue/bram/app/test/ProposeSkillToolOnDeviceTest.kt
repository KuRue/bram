package io.github.kurue.bram.app.test

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.app.ProposeSkillTool
import io.github.kurue.bram.core.domain.SkillActionOutcome
import io.github.kurue.bram.core.domain.SkillOrigin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the agent's propose_skill path against the real on-device skill store: the tool
 * assembles a SKILL.md, proposeDraft stages it with no active version (so it stays out of the
 * prompt), and activateDraft promotes it. The pure state machine is covered in core:domain;
 * this is the persistent-store + tool half.
 */
@RunWith(AndroidJUnit4::class)
class ProposeSkillToolOnDeviceTest {

    @Test
    fun proposedSkillLandsAsADraftTheUserMustActivate() = runBlocking {
        val store = ApplicationProvider.getApplicationContext<BramApplication>().container.skillStore
        val tool = ProposeSkillTool(store)
        val arguments = JSONObject()
            .put("name", "Verify Draft")
            .put("version", "1.0.0")
            .put("description", "A check that propose_skill stages a draft the user must activate")
            .put("instructions", "Run the verification step, then report the result.")
            .toString()

        try {
            val result = JSONObject(tool.execute(arguments))
            assertEquals("verify-draft", result.getString("packageId"))

            val pkg = store.packages().first { it.id == "verify-draft" }
            assertNull("a proposed skill must not be active on arrival", pkg.activeVersion)
            assertEquals("1.0.0", pkg.draftVersion)
            assertTrue(
                "a draft-only skill must be kept out of the prompt",
                store.activeSkills().none { it.id == "verify-draft" },
            )

            assertEquals(SkillActionOutcome.Ok, store.activateDraft("verify-draft"))
            assertEquals("1.0.0", store.packages().first { it.id == "verify-draft" }.activeVersion)
            assertTrue(store.activeSkills().any { it.id == "verify-draft" })

            // Provenance and approval survive the round trip through skills.json: the version is
            // agent-authored, activation is what approved it, and a rollback can only land on
            // something approved.
            val activated = store.packages().first { it.id == "verify-draft" }.versions.single()
            assertEquals(SkillOrigin.AGENT, activated.origin)
            assertTrue("activation must record the approval", activated.approvedAtEpochMillis != null)

            // Disable leaves it installed but out of the prompt; enable brings it back.
            assertEquals(SkillActionOutcome.Ok, store.disable("verify-draft"))
            assertTrue(store.activeSkills().none { it.id == "verify-draft" })
            assertTrue("disable keeps the package", store.packages().any { it.id == "verify-draft" })
            assertEquals(SkillActionOutcome.Ok, store.enable("verify-draft"))
            assertTrue(store.activeSkills().any { it.id == "verify-draft" })
        } finally {
            store.remove("verify-draft")
        }
    }
}
