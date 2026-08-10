package io.github.kurue.bram.app.test

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.kurue.bram.app.MainActivity
import org.junit.Rule
import org.junit.Test

/**
 * Smoke tests for the shell that hosts every conversation: launch, compose, and the
 * drawer/panel navigation. Deliberately free of model state — nothing here loads a runtime,
 * so the same suite runs on any device with the app installed.
 */
class ChatScreenSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chatChromeRendersOnLaunch() {
        composeRule.onNodeWithTag("composer-field").assertIsDisplayed()
        composeRule.onNodeWithText("Message Bram").assertIsDisplayed()
        composeRule.onNodeWithTag("menu-button").assertIsDisplayed()
        composeRule.onNodeWithTag("new-chat").assertIsDisplayed()
        composeRule.onNodeWithTag("model-pill").assertIsDisplayed()
    }

    @Test
    fun composerAcceptsText() {
        composeRule.onNodeWithTag("composer-field").performTextInput("hello")
        composeRule.onNodeWithTag("composer-field").assertTextContains("hello")
    }

    @Test
    fun drawerOpensAndSettingsPanelShows() {
        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.onNodeWithText("New conversation").assertIsDisplayed()
        composeRule.onNodeWithText("Conversations").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Remote providers").assertIsDisplayed()
    }

    @Test
    fun drawerOpensAndModelsPanelShows() {
        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.onNodeWithText("Models").performClick()
        composeRule.onNodeWithText("Profiles").assertIsDisplayed()
        composeRule.onNodeWithText("Import model").assertIsDisplayed()
    }
}
