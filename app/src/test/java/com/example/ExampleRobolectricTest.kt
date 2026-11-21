package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun appNameMatchesDevForgeAi() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("DevForge AI", context.getString(R.string.app_name))
  }

  @Test
  fun createProjectAndNavigateAcrossIdeScreens() {
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("nav_projects").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("create_project_button").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("new_project_name_input").performTextClearance()
    composeRule.onNodeWithTag("new_project_name_input").performTextInput("RoboTestApp")
    composeRule.onNodeWithTag("confirm_create_project_btn").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText("RoboTestApp", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onAllNodesWithText("RoboTestApp", substring = true).onFirst().assertIsDisplayed()

    composeRule.onNodeWithTag("topbar_build_btn").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("run_build_btn").assertIsDisplayed()

    composeRule.onNodeWithTag("topbar_sdk_btn").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("nav_terminal").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("terminal_command_input").assertIsDisplayed()

    composeRule.onNodeWithTag("nav_ai").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("ai_prompt_input").assertIsDisplayed()

    composeRule.onNodeWithTag("topbar_settings_btn").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("settings_dark_theme_switch").assertIsDisplayed()
  }
}
