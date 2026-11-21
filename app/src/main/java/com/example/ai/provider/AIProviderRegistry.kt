package com.example.ai.provider

import com.example.core.common.AppDispatchers
import com.example.core.common.RedactingLogger
import com.example.core.database.AiModelDao
import com.example.core.database.AiProviderDao
import com.example.core.database.AiProviderEntity
import com.example.core.preferences.OnlineProviderType
import com.example.core.preferences.PreferencesRepository
import com.example.core.security.SecretCipher
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AIProviderRegistry(
  private val aiProviderDao: AiProviderDao,
  private val aiModelDao: AiModelDao,
  private val secretCipher: SecretCipher,
  private val preferencesRepository: PreferencesRepository,
  private val transport: AITransport,
  private val dispatchers: AppDispatchers,
) {
  private val adapters: Map<OnlineProviderType, AIProvider> =
    mapOf(
      OnlineProviderType.GEMINI to GeminiProviderAdapter(transport),
      OnlineProviderType.OPENAI to OpenAiProviderAdapter(OnlineProviderType.OPENAI, transport),
      OnlineProviderType.CUSTOM_OPENAI to OpenAiProviderAdapter(OnlineProviderType.CUSTOM_OPENAI, transport),
      OnlineProviderType.ANTHROPIC to AnthropicProviderAdapter(transport),
      OnlineProviderType.CUSTOM_ADAPTER to CustomRestProviderAdapter(transport),
    )

  fun adapterFor(type: OnlineProviderType): AIProvider =
    adapters[type] ?: error("No AIProvider adapter registered for $type")

  fun observeProviders(): Flow<List<AIProviderConfig>> =
    aiProviderDao.observeAll().map { rows -> rows.map { it.toConfig() } }

  suspend fun getProviders(): List<AIProviderConfig> =
    withContext(dispatchers.io) { aiProviderDao.getAll().map { it.toConfig() } }

  suspend fun getProvider(id: String): AIProviderConfig? =
    withContext(dispatchers.io) { aiProviderDao.getById(id)?.toConfig() }

  suspend fun migrateLegacyPreferencesIfNeeded() =
    withContext(dispatchers.io) {
      if (aiProviderDao.getAll().isNotEmpty()) return@withContext
      val prefs = preferencesRepository.preferences.first()
      if (!prefs.hasApiKey && prefs.defaultProviderId == null) return@withContext

      val now = System.currentTimeMillis()
      val id = "legacy_${prefs.onlineProvider.name.lowercase()}"
      val baseUrl =
        prefs.customEndpointUrl.takeIf { it.isNotBlank() } ?: prefs.onlineProvider.defaultBaseUrl
      val entity =
        AiProviderEntity(
          id = id,
          displayName = prefs.onlineProvider.displayName,
          providerType = prefs.onlineProvider.name,
          baseUrl = baseUrl,
          encryptedApiKey = prefs.encryptedApiKey,
          apiVersion = if (prefs.onlineProvider == OnlineProviderType.ANTHROPIC) "2023-06-01" else "",
          authMethod = AIProviderConfig.defaultAuthFor(prefs.onlineProvider).name,
          customAuthHeaderName = "",
          defaultModelId = prefs.onlineModelId.ifBlank { prefs.onlineProvider.defaultModel },
          requestTimeoutSeconds = 60,
          maxOutputTokens = 4096,
          customHeadersJson = "{}",
          customConfigJson = "{}",
          supportsStreaming = true,
          isEnabled = true,
          lastConnectionStatus = ConnectionStatus.UNKNOWN.name,
          lastConnectionMessage = "Migrated from previous settings",
          lastTestedAtMillis = 0L,
          createdAtMillis = now,
          updatedAtMillis = now,
        )
      aiProviderDao.upsert(entity)
      val modelId = entity.defaultModelId
      val compositeId =
        if (modelId.isNotBlank()) AIModel.makeCompositeId(id, modelId) else null
      preferencesRepository.setDefaultProviderAndModels(
        defaultProviderId = id,
        defaultChatModelCompositeId = compositeId,
        agentModelCompositeId = compositeId,
      )
    }

  suspend fun saveProvider(
    id: String? = null,
    displayName: String,
    providerType: OnlineProviderType,
    baseUrl: String,
    newPlainApiKey: String? = null,
    clearApiKey: Boolean = false,
    apiVersion: String = "",
    authMethod: AuthMethod = AIProviderConfig.defaultAuthFor(providerType),
    customAuthHeaderName: String = "",
    defaultModelId: String = providerType.defaultModel,
    requestTimeoutSeconds: Int = 60,
    maxOutputTokens: Int = 4096,
    customHeaders: Map<String, String> = emptyMap(),
    customConfig: Map<String, String> = emptyMap(),
    supportsStreaming: Boolean = true,
    isEnabled: Boolean = true,
  ): Result<AIProviderConfig> =
    withContext(dispatchers.io) {
      val cleanName = displayName.trim()
      if (cleanName.isEmpty()) {
        return@withContext Result.failure(IllegalArgumentException("Provider display name is required."))
      }
      val urlCheck = ProviderUrlValidator.validate(baseUrl)
      if (!urlCheck.isValid) {
        return@withContext Result.failure(IllegalArgumentException(urlCheck.errorMessage ?: "Invalid Base URL."))
      }

      val existing = id?.let { aiProviderDao.getById(it) }
      val resolvedId = existing?.id ?: id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
      val encryptedKey =
        when {
          clearApiKey -> ""
          !newPlainApiKey.isNullOrBlank() -> secretCipher.encrypt(newPlainApiKey.trim())
          else -> existing?.encryptedApiKey.orEmpty()
        }

      val now = System.currentTimeMillis()
      val entity =
        AiProviderEntity(
          id = resolvedId,
          displayName = cleanName,
          providerType = providerType.name,
          baseUrl = urlCheck.normalizedUrl,
          encryptedApiKey = encryptedKey,
          apiVersion = apiVersion.trim(),
          authMethod = authMethod.name,
          customAuthHeaderName = customAuthHeaderName.trim(),
          defaultModelId = defaultModelId.trim(),
          requestTimeoutSeconds = requestTimeoutSeconds.coerceIn(5, 300),
          maxOutputTokens = maxOutputTokens.coerceIn(64, 65536),
          customHeadersJson = mapToJsonString(customHeaders),
          customConfigJson = mapToJsonString(customConfig),
          supportsStreaming = supportsStreaming,
          isEnabled = isEnabled,
          lastConnectionStatus = existing?.lastConnectionStatus ?: ConnectionStatus.UNKNOWN.name,
          lastConnectionMessage =
            urlCheck.securityWarning ?: existing?.lastConnectionMessage.orEmpty(),
          lastTestedAtMillis = existing?.lastTestedAtMillis ?: 0L,
          createdAtMillis = existing?.createdAtMillis ?: now,
          updatedAtMillis = now,
        )
      aiProviderDao.upsert(entity)
      Result.success(entity.toConfig())
    }

  suspend fun setProviderEnabled(providerId: String, enabled: Boolean) =
    withContext(dispatchers.io) {
      val existing = aiProviderDao.getById(providerId) ?: return@withContext
      aiProviderDao.upsert(existing.copy(isEnabled = enabled, updatedAtMillis = System.currentTimeMillis()))
    }

  suspend fun deleteProvider(providerId: String) =
    withContext(dispatchers.io) {
      aiModelDao.deleteForProvider(providerId)
      aiProviderDao.deleteById(providerId)
      val prefs = preferencesRepository.preferences.first()
      if (prefs.defaultProviderId == providerId) {
        val next = aiProviderDao.getAll().firstOrNull()
        preferencesRepository.setDefaultProviderAndModels(
          defaultProviderId = next?.id.orEmpty(),
          defaultChatModelCompositeId = "",
          agentModelCompositeId = "",
        )
      }
    }

  suspend fun clearProviderApiKey(providerId: String) =
    withContext(dispatchers.io) {
      val existing = aiProviderDao.getById(providerId) ?: return@withContext
      aiProviderDao.upsert(
        existing.copy(
          encryptedApiKey = "",
          lastConnectionStatus = ConnectionStatus.UNKNOWN.name,
          lastConnectionMessage = "API key cleared",
          updatedAtMillis = System.currentTimeMillis(),
        )
      )
    }

  suspend fun testConnection(providerId: String): ProviderTestResult =
    withContext(dispatchers.io) {
      val entity =
        aiProviderDao.getById(providerId)
          ?: return@withContext ProviderTestResult(false, message = "Provider not found.")
      val config = entity.toConfig()
      val plainKey = decryptKey(config.encryptedApiKey)
      val adapter = adapterFor(config.providerType)
      val result = adapter.testConnection(config, plainKey)

      val status = if (result.success) ConnectionStatus.CONNECTED else ConnectionStatus.ERROR
      val safeMsg = RedactingLogger.redact(result.message, listOf(plainKey))
      aiProviderDao.upsert(
        entity.copy(
          lastConnectionStatus = status.name,
          lastConnectionMessage = safeMsg,
          lastTestedAtMillis = System.currentTimeMillis(),
          updatedAtMillis = System.currentTimeMillis(),
        )
      )
      result.copy(message = safeMsg)
    }

  fun decryptKey(encryptedApiKey: String): String =
    if (encryptedApiKey.isBlank()) "" else secretCipher.decrypt(encryptedApiKey)

  private fun AiProviderEntity.toConfig(): AIProviderConfig {
    val type = OnlineProviderType.entries.firstOrNull { it.name == providerType } ?: OnlineProviderType.CUSTOM_OPENAI
    val auth = AuthMethod.entries.firstOrNull { it.name == authMethod } ?: AIProviderConfig.defaultAuthFor(type)
    val conn = ConnectionStatus.entries.firstOrNull { it.name == lastConnectionStatus } ?: ConnectionStatus.UNKNOWN
    return AIProviderConfig(
      id = id,
      displayName = displayName,
      providerType = type,
      baseUrl = baseUrl,
      encryptedApiKey = encryptedApiKey,
      apiVersion = apiVersion,
      authMethod = auth,
      customAuthHeaderName = customAuthHeaderName,
      defaultModelId = defaultModelId,
      requestTimeoutSeconds = requestTimeoutSeconds,
      maxOutputTokens = maxOutputTokens,
      customHeaders = jsonStringToMap(customHeadersJson),
      customConfig = jsonStringToMap(customConfigJson),
      supportsStreaming = supportsStreaming,
      isEnabled = isEnabled,
      lastConnectionStatus = conn,
      lastConnectionMessage = lastConnectionMessage,
      lastTestedAtMillis = lastTestedAtMillis,
    )
  }

  private fun mapToJsonString(map: Map<String, String>): String {
    val obj = JSONObject()
    map.forEach { (k, v) ->
      if (k.isNotBlank()) obj.put(k.trim(), v.trim())
    }
    return obj.toString()
  }

  private fun jsonStringToMap(jsonStr: String): Map<String, String> =
    runCatching {
      val obj = JSONObject(jsonStr.ifBlank { "{}" })
      buildMap {
        val keys = obj.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          put(k, obj.optString(k, ""))
        }
      }
    }.getOrDefault(emptyMap())
}
