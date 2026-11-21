package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
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
  PROJECTS("projects", "Project Manager", "Projects", Icons.Default.Folder, "nav_projects", true),
  PROJECT_IMPORT("project_import", "Project Import", "Import", Icons.Default.FileUpload, "nav_project_import", false),
  EDITOR("editor", "Code Editor", "Editor", Icons.Default.Code, "nav_editor", true),
  TERMINAL("terminal", "Terminal", "Shell", Icons.Default.Terminal, "nav_terminal", true),
  BUILD("build_manager", "Build Manager", "Build", Icons.Default.Build, "nav_build", false),
  SDK("sdk_manager", "SDK Manager", "SDK", Icons.Default.Extension, "nav_sdk", false),
  AI("ai_assistant", "AI Chat & Agent", "AI", Icons.Default.AutoAwesome, "nav_ai", true),
  AI_PROVIDERS("ai_providers", "AI Providers", "Providers", Icons.Default.Hub, "nav_ai_providers", false),
  MODEL_SELECTOR("model_selector", "Model Selector", "Models", Icons.Default.Tune, "nav_model_selector", false),
  MCP_MANAGER("mcp_manager", "MCP Servers", "MCP", Icons.Default.Dns, "nav_mcp_manager", false),
  PROTECTION("protection", "Code Protection", "Protect", Icons.Default.Shield, "nav_protection", false),
  MAPPING_VAULT("mapping_vault", "Mapping Vault", "Vault", Icons.Default.Lock, "nav_mapping_vault", false),
  BUILD_HISTORY("build_history", "Build History", "History", Icons.Default.History, "nav_build_history", false),
  SETTINGS("settings", "Settings", "Settings", Icons.Default.Settings, "nav_settings", false),
}
