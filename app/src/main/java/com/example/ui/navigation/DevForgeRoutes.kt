package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

enum class IdeRoute(
  val route: String,
  val label: String,
  val shortLabel: String,
  val icon: ImageVector,
  val testTag: String,
  val primaryBottomNav: Boolean,
) {
  DASHBOARD("dashboard", "Dashboard", "Home", Icons.Default.Dashboard, "nav_dashboard", true),
  PROJECTS("projects", "Projects", "Projects", Icons.Default.Folder, "nav_projects", true),
  EDITOR("editor", "Code Editor", "Editor", Icons.Default.Code, "nav_editor", true),
  AI("ai_assistant", "AI Assistant", "AI", Icons.Default.AutoAwesome, "nav_ai", true),
  TERMINAL("terminal", "Terminal", "Shell", Icons.Default.Terminal, "nav_terminal", true),
  BUILD("build_manager", "Build Manager", "Build", Icons.Default.Build, "nav_build", false),
  SDK("sdk_manager", "SDK Manager", "SDK", Icons.Default.Extension, "nav_sdk", false),
  SETTINGS("settings", "Settings", "Settings", Icons.Default.Settings, "nav_settings", false),
}
