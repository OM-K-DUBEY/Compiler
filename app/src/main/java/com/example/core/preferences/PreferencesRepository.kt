package com.example.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.devForgeDataStore: DataStore<Preferences> by preferencesDataStore(name = "devforge_settings")

enum class AiMode {
  AUTOMATIC,
  ONLINE,
  OFFLINE,
}

enum class OnlineProviderType(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
  GEMINI(
    displayName = "Google Gemini",
    defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
    defaultModel = "gemini-2.5-flash",
  ),
  OPENAI(
    displayName = "OpenAI",
    defaultBaseUrl = "https://api.openai.com/v1",
    defaultModel = "gpt-4o-mini",
  ),
  ANTHROPIC(
    displayName = "Anthropic Claude",
    defaultBaseUrl = "https://api.anthropic.com/v1",
    defaultModel = "claude-3-5-sonnet-latest",
  ),
  CUSTOM_OPENAI(
    displayName = "Custom OpenAI-Compatible",
    defaultBaseUrl = "https://api.openai.com/v1",
    defaultModel = "llama-3.3-70b-versatile",
  ),
}

data class IdePreferences(
  val darkTheme: Boolean = true,
  val editorFontSizeSp: Int = 13,
  val editorTabSize: Int = 4,
  val editorWordWrap: Boolean = false,
  val autoSaveEnabled: Boolean = true,
  val activeProjectId: String? = null,
  val aiMode: AiMode = AiMode.AUTOMATIC,
  val onlineProvider: OnlineProviderType = OnlineProviderType.GEMINI,
  val onlineModelId: String = OnlineProviderType.GEMINI.defaultModel,
  val customEndpointUrl: String = OnlineProviderType.CUSTOM_OPENAI.defaultBaseUrl,
  val encryptedApiKey: String = "",
  val hasApiKey: Boolean = false,
  val allowUploadingProjectFilesToOnlineAi: Boolean = false,
  val selectedOfflineModelPath: String? = null,
  val offlineContextSize: Int = 4096,
  val offlineTemperature: Float = 0.2f,
  val offlineThreads: Int = 4,
  val customToolchainPath: String = "",
)

class PreferencesRepository(
  private val context: Context,
  private val secretCipher: SecretCipher,
) {
  private object Keys {
    val DARK_THEME = booleanPreferencesKey("dark_theme")
    val FONT_SIZE = intPreferencesKey("editor_font_size")
    val TAB_SIZE = intPreferencesKey("editor_tab_size")
    val WORD_WRAP = booleanPreferencesKey("editor_word_wrap")
    val AUTO_SAVE = booleanPreferencesKey("auto_save_enabled")
    val ACTIVE_PROJECT_ID = stringPreferencesKey("active_project_id")
    val AI_MODE = stringPreferencesKey("ai_mode")
    val ONLINE_PROVIDER = stringPreferencesKey("online_provider")
    val ONLINE_MODEL_ID = stringPreferencesKey("online_model_id")
    val CUSTOM_ENDPOINT_URL = stringPreferencesKey("custom_endpoint_url")
    val ENCRYPTED_API_KEY = stringPreferencesKey("encrypted_api_key")
    val ALLOW_CODE_UPLOAD = booleanPreferencesKey("allow_code_upload")
    val OFFLINE_MODEL_PATH = stringPreferencesKey("offline_model_path")
    val OFFLINE_CONTEXT_SIZE = intPreferencesKey("offline_context_size")
    val OFFLINE_TEMPERATURE = floatPreferencesKey("offline_temperature")
    val OFFLINE_THREADS = intPreferencesKey("offline_threads")
    val CUSTOM_TOOLCHAIN_PATH = stringPreferencesKey("custom_toolchain_path")
  }

  val preferences: Flow<IdePreferences> =
    context.devForgeDataStore.data.map { prefs ->
      val providerName = prefs[Keys.ONLINE_PROVIDER]
      val provider =
        OnlineProviderType.entries.firstOrNull { it.name == providerName }
          ?: OnlineProviderType.GEMINI
      val modeName = prefs[Keys.AI_MODE]
      val aiMode = AiMode.entries.firstOrNull { it.name == modeName } ?: AiMode.AUTOMATIC
      val encKey = prefs[Keys.ENCRYPTED_API_KEY].orEmpty()

      IdePreferences(
        darkTheme = prefs[Keys.DARK_THEME] ?: true,
        editorFontSizeSp = prefs[Keys.FONT_SIZE] ?: 13,
        editorTabSize = prefs[Keys.TAB_SIZE] ?: 4,
        editorWordWrap = prefs[Keys.WORD_WRAP] ?: false,
        autoSaveEnabled = prefs[Keys.AUTO_SAVE] ?: true,
        activeProjectId = prefs[Keys.ACTIVE_PROJECT_ID],
        aiMode = aiMode,
        onlineProvider = provider,
        onlineModelId = prefs[Keys.ONLINE_MODEL_ID] ?: provider.defaultModel,
        customEndpointUrl = prefs[Keys.CUSTOM_ENDPOINT_URL] ?: provider.defaultBaseUrl,
        encryptedApiKey = encKey,
        hasApiKey = encKey.isNotBlank(),
        allowUploadingProjectFilesToOnlineAi = prefs[Keys.ALLOW_CODE_UPLOAD] ?: false,
        selectedOfflineModelPath = prefs[Keys.OFFLINE_MODEL_PATH],
        offlineContextSize = prefs[Keys.OFFLINE_CONTEXT_SIZE] ?: 4096,
        offlineTemperature = prefs[Keys.OFFLINE_TEMPERATURE] ?: 0.2f,
        offlineThreads = prefs[Keys.OFFLINE_THREADS] ?: 4,
        customToolchainPath = prefs[Keys.CUSTOM_TOOLCHAIN_PATH].orEmpty(),
      )
    }

  suspend fun setDarkTheme(enabled: Boolean) {
    context.devForgeDataStore.edit { it[Keys.DARK_THEME] = enabled }
  }

  suspend fun setEditorSettings(fontSizeSp: Int, tabSize: Int, wordWrap: Boolean, autoSave: Boolean) {
    context.devForgeDataStore.edit {
      it[Keys.FONT_SIZE] = fontSizeSp.coerceIn(10, 22)
      it[Keys.TAB_SIZE] = tabSize.coerceIn(2, 8)
      it[Keys.WORD_WRAP] = wordWrap
      it[Keys.AUTO_SAVE] = autoSave
    }
  }

  suspend fun setActiveProjectId(projectId: String?) {
    context.devForgeDataStore.edit {
      if (projectId == null) it.remove(Keys.ACTIVE_PROJECT_ID)
      else it[Keys.ACTIVE_PROJECT_ID] = projectId
    }
  }

  suspend fun setAiMode(mode: AiMode) {
    context.devForgeDataStore.edit { it[Keys.AI_MODE] = mode.name }
  }

  suspend fun setOnlineAiConfig(
    provider: OnlineProviderType,
    modelId: String,
    customEndpointUrl: String,
    plainApiKey: String?,
  ) {
    context.devForgeDataStore.edit { prefs ->
      prefs[Keys.ONLINE_PROVIDER] = provider.name
      prefs[Keys.ONLINE_MODEL_ID] = modelId.ifBlank { provider.defaultModel }
      prefs[Keys.CUSTOM_ENDPOINT_URL] = customEndpointUrl.ifBlank { provider.defaultBaseUrl }
      if (plainApiKey != null) {
        if (plainApiKey.isBlank()) {
          prefs.remove(Keys.ENCRYPTED_API_KEY)
        } else {
          prefs[Keys.ENCRYPTED_API_KEY] = secretCipher.encrypt(plainApiKey.trim())
        }
      }
    }
  }

  suspend fun clearApiKey() {
    context.devForgeDataStore.edit { it.remove(Keys.ENCRYPTED_API_KEY) }
  }

  fun decryptApiKey(encryptedApiKey: String): String {
    if (encryptedApiKey.isBlank()) return ""
    return secretCipher.decrypt(encryptedApiKey)
  }

  suspend fun setAllowUploadingProjectFilesToOnlineAi(allowed: Boolean) {
    context.devForgeDataStore.edit { it[Keys.ALLOW_CODE_UPLOAD] = allowed }
  }

  suspend fun setOfflineAiConfig(
    modelPath: String?,
    contextSize: Int,
    temperature: Float,
    threads: Int,
  ) {
    context.devForgeDataStore.edit {
      if (modelPath.isNullOrBlank()) it.remove(Keys.OFFLINE_MODEL_PATH)
      else it[Keys.OFFLINE_MODEL_PATH] = modelPath
      it[Keys.OFFLINE_CONTEXT_SIZE] = contextSize.coerceIn(512, 32768)
      it[Keys.OFFLINE_TEMPERATURE] = temperature.coerceIn(0f, 2f)
      it[Keys.OFFLINE_THREADS] = threads.coerceIn(1, 16)
    }
  }

  suspend fun setCustomToolchainPath(path: String) {
    context.devForgeDataStore.edit { it[Keys.CUSTOM_TOOLCHAIN_PATH] = path.trim() }
  }
}
