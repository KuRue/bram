package io.github.kurue.bram.app.test

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The draft-review path on a real store and a real screen: a skill with an active version and a
 * staged draft must offer "Review changes", and the diff must name both the line the draft adds
 * and the line it replaces.
 */
@RunWith(AndroidJUnit4::class)
class SkillDraftReviewOnDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun aDraftCanBeReviewedAgainstTheActiveVersion() {
        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.onNodeWithText("Capabilities").performClick()
        composeRule.onNodeWithText("Skills").performClick()
        // Wait for the card's own review button rather than the screen text: the seed is a draft
        // skill, so the button is the thing that proves the list rendered.
        composeRule.waitUntil(TIMEOUT_MILLIS) { hasTag("skill-review") }

        composeRule.onNodeWithTag("skill-review").performScrollTo().performClick()

        composeRule.waitUntil(TIMEOUT_MILLIS) { hasText("Wipe your shoes on the mat.") }
        composeRule.onNodeWithText("+ Wipe your shoes on the mat.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("- Always knock before entering.", substring = true).assertIsDisplayed()
    }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun hasTag(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    companion object {
        private const val TIMEOUT_MILLIS = 60_000L

        @BeforeClass
        @JvmStatic
        fun seedSkillWithDraft() {
            val store = ApplicationProvider.getApplicationContext<BramApplication>().container.skillStore
            runBlocking {
                store.importDocument(document("1.0.0", "Always knock before entering."))
                store.proposeDraft(
                    document(
                        "1.1.0",
                        "Always knock twice before entering.\nWipe your shoes on the mat.",
                    ),
                )
            }
        }

        @AfterClass
        @JvmStatic
        fun removeSeededSkill() {
            val store = ApplicationProvider.getApplicationContext<BramApplication>().container.skillStore
            runBlocking { store.remove("review-demo") }
        }

        private fun document(version: String, body: String) = buildString {
            // Not trimIndent: a multi-line body has an unindented second line, which would make
            // the minimum indent zero and leave the front matter indented (and unparseable).
            appendLine("---")
            appendLine("name: Review Demo")
            appendLine("version: $version")
            appendLine("description: A skill seeded to exercise the draft review screen")
            appendLine("---")
            append(body)
        }
    }
}
