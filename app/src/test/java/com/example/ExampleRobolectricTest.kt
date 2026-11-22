package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
  fun createProjectAndNavigateAcrossAllIdeAndUpgradeScreens() {
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

    composeRule.onNodeWithTag("topbar_protection_btn").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("open_mapping_vault_btn").assertIsDisplayed()

    composeRule.onNodeWithTag("nav_terminal").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("terminal_command_input").assertIsDisplayed()

    composeRule.onNodeWithTag("nav_ai").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("ai_prompt_input").assertIsDisplayed()

    // Navigate via Drawer to AI Providers, Model Selector, MCP Manager, Mapping Vault, Build History, SDK, Import
    navigateViaDrawer("drawer_ai_providers")
    composeRule.onNodeWithTag("add_ai_provider_btn").assertIsDisplayed()

    navigateViaDrawer("drawer_model_selector")
    composeRule.onNodeWithTag("add_manual_model_btn").assertIsDisplayed()

    navigateViaDrawer("drawer_mcp_manager")
    composeRule.onNodeWithTag("add_mcp_server_btn").assertIsDisplayed()

    navigateViaDrawer("drawer_mapping_vault")
    composeRule.waitForIdle()

    navigateViaDrawer("drawer_build_history")
    composeRule.waitForIdle()

    navigateViaDrawer("drawer_project_import")
    composeRule.onNodeWithTag("project_import_zip_btn").assertIsDisplayed()

    navigateViaDrawer("drawer_sdk_manager")
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("topbar_settings_btn").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("settings_dark_theme_switch").assertIsDisplayed()
    // Toggle Light & Dark Theme cleanly
    composeRule.onNodeWithTag("settings_dark_theme_switch").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("settings_dark_theme_switch").performClick()
    composeRule.waitForIdle()
  }

  @Test
  fun importWizardExcludesRemovedLanguagesAndEditorOpensProject() {
    composeRule.waitForIdle()
    navigateViaDrawer("drawer_project_import")

    composeRule.onNodeWithTag("import_template_name_input").performScrollTo()
    composeRule.waitForIdle()

    // Verify retained frameworks exist and removed ones do not
    composeRule.onAllNodesWithText("Android (Kotlin + Compose)", substring = true).onFirst().performScrollTo().assertIsDisplayed()
    composeRule.onAllNodesWithText("Web (HTML / CSS / JS)", substring = true).onFirst().performScrollTo().assertIsDisplayed()
    assertEquals(0, composeRule.onAllNodesWithText("Python", substring = true).fetchSemanticsNodes().size)
    assertEquals(0, composeRule.onAllNodesWithText("C++", substring = true).fetchSemanticsNodes().size)
    assertEquals(0, composeRule.onAllNodesWithText("React Native", substring = true).fetchSemanticsNodes().size)

    // Create starter project and open editor
    composeRule.onNodeWithTag("import_template_name_input").performScrollTo()
    composeRule.onNodeWithTag("import_template_name_input").performTextClearance()
    composeRule.onNodeWithTag("import_template_name_input").performTextInput("EditorVerifyApp")
    composeRule.onNodeWithTag("import_create_template_btn").performScrollTo().performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText("EditorVerifyApp", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithTag("nav_editor").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText("MainActivity.kt", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("code_editor_field").assertIsDisplayed()
  }

  private fun navigateViaDrawer(drawerItemTag: String) {
    composeRule.onNodeWithTag("open_drawer_btn").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(drawerItemTag).performScrollTo().performClick()
    composeRule.waitForIdle()
  }
}
