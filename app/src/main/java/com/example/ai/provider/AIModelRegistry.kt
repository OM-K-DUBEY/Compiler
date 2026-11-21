package com.example.ai.provider

import com.example.core.common.AppDispatchers
import com.example.core.database.AiModelDao
import com.example.core.database.AiModelEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class AIModelRegistry(
  private val aiModelDao: AiModelDao,
  private val providerRegistry: AIProviderRegistry,
  private val dispatchers: AppDispatchers,
) {
  private val _conversationModels = MutableStateFlow<Map<String, String>>(emptyMap())
  val conversationModels: StateFlow<Map<String, String>> = _conversationModels.asStateFlow()

  fun observeAllModels(): Flow<List<AIModel>> =
    aiModelDao.observeAll().map { rows -> rows.map { it.toDomain() } }

  fun observeModelsForProvider(providerId: String): Flow<List<AIModel>> =
    aiModelDao.observeForProvider(providerId).map { rows -> rows.map { it.toDomain() } }

  suspend fun getModelByCompositeId(compositeId: String): AIModel? =
    withContext(dispatchers.io) { aiModelDao.getByCompositeId(compositeId)?.toDomain() }

  fun setConversationModel(conversationKey: String, compositeId: String) {
    _conversationModels.update { it + (conversationKey to compositeId) }
  }

  fun getConversationModel(conversationKey: String): String? =
    _conversationModels.value[conversationKey]

  suspend fun refreshModels(providerId: String): Result<List<AIModel>> =
    withContext(dispatchers.io) {
      val config =
        providerRegistry.getProvider(providerId)
          ?: return@withContext Result.failure(IllegalArgumentException("Provider not found."))
      if (!config.isEnabled) {
        return@withContext Result.failure(IllegalStateException("Provider '${config.displayName}' is disabled."))
      }

      val plainKey = providerRegistry.decryptKey(config.encryptedApiKey)
      val adapter = providerRegistry.adapterFor(config.providerType)
      val discoveredRes = adapter.listModels(config, plainKey)
      if (discoveredRes.isFailure) {
        return@withContext discoveredRes
      }

      val discovered = discoveredRes.getOrThrow()
      val existing = aiModelDao.getForProvider(providerId)
      val existingMap = existing.associateBy { it.modelId }
      val discoveredIds = discovered.map { it.modelId }.toSet()
      val now = System.currentTimeMillis()

      val toUpsert = mutableListOf<AiModelEntity>()
      for (model in discovered) {
        val prev = existingMap[model.modelId]
        val preservedStatus =
          if (prev != null && prev.availabilityStatus == ModelAvailabilityStatus.VERIFIED.name) {
            ModelAvailabilityStatus.VERIFIED
          } else {
            ModelAvailabilityStatus.DISCOVERED
          }
        toUpsert +=
          model.copy(
            availabilityStatus = preservedStatus,
            lastVerifiedAtMillis = prev?.lastVerifiedAtMillis ?: 0L,
          ).toEntity(now)
      }

      // Detect previously discovered models that disappeared from the provider's model endpoint
      for (prev in existing) {
        if (!prev.isManual && prev.modelId !in discoveredIds) {
          toUpsert +=
            prev.copy(
              availabilityStatus = ModelAvailabilityStatus.REMOVED.name,
              updatedAtMillis = now,
            )
        }
      }

      if (toUpsert.isNotEmpty()) {
        aiModelDao.upsertAll(toUpsert)
      }
      Result.success(aiModelDao.getForProvider(providerId).map { it.toDomain() })
    }

  suspend fun addOrUpdateManualModel(
    providerId: String,
    modelId: String,
    displayName: String = modelId,
    contextWindow: Int? = null,
    maxOutputTokens: Int? = null,
    visionCapability: CapabilitySupport = CapabilitySupport.UNKNOWN,
    toolCallingCapability: CapabilitySupport = CapabilitySupport.UNKNOWN,
    streamingCapability: CapabilitySupport = CapabilitySupport.UNKNOWN,
  ): Result<AIModel> =
    withContext(dispatchers.io) {
      val cleanId = modelId.trim()
      if (cleanId.isEmpty()) {
        return@withContext Result.failure(IllegalArgumentException("Model ID cannot be empty."))
      }
      val compositeId = AIModel.makeCompositeId(providerId, cleanId)
      val existing = aiModelDao.getByCompositeId(compositeId)
      val model =
        AIModel(
          compositeId = compositeId,
          providerId = providerId,
          modelId = cleanId,
          displayName = displayName.trim().ifEmpty { cleanId },
          contextWindow = contextWindow?.takeIf { it > 0 },
          maxOutputTokens = maxOutputTokens?.takeIf { it > 0 },
          inputModalities = existing?.inputModalities ?: "UNKNOWN",
          outputModalities = existing?.outputModalities ?: "UNKNOWN",
          visionCapability = visionCapability,
          toolCallingCapability = toolCallingCapability,
          streamingCapability = streamingCapability,
          availabilityStatus =
            if (existing?.availabilityStatus == ModelAvailabilityStatus.VERIFIED.name) {
              ModelAvailabilityStatus.VERIFIED
            } else {
              ModelAvailabilityStatus.MANUAL
            },
          isManual = true,
          lastVerifiedAtMillis = existing?.lastVerifiedAtMillis ?: 0L,
        )
      aiModelDao.upsert(model.toEntity(System.currentTimeMillis()))
      Result.success(model)
    }

  suspend fun verifyModel(compositeId: String): ProviderTestResult =
    withContext(dispatchers.io) {
      val entity =
        aiModelDao.getByCompositeId(compositeId)
          ?: return@withContext ProviderTestResult(false, message = "Model not found in registry.")
      val config =
        providerRegistry.getProvider(entity.providerId)
          ?: return@withContext ProviderTestResult(false, message = "Parent provider not found.")
      if (!config.isEnabled) {
        return@withContext ProviderTestResult(false, message = "Provider '${config.displayName}' is disabled.")
      }

      val plainKey = providerRegistry.decryptKey(config.encryptedApiKey)
      val adapter = providerRegistry.adapterFor(config.providerType)
      val result = adapter.verifyModel(config, plainKey, entity.modelId)
      val now = System.currentTimeMillis()
      val newStatus =
        if (result.success) ModelAvailabilityStatus.VERIFIED else ModelAvailabilityStatus.UNAVAILABLE
      aiModelDao.upsert(
        entity.copy(
          availabilityStatus = newStatus.name,
          lastVerifiedAtMillis = if (result.success) now else entity.lastVerifiedAtMillis,
          updatedAtMillis = now,
        )
      )
      result
    }

  suspend fun deleteModel(compositeId: String) =
    withContext(dispatchers.io) { aiModelDao.deleteByCompositeId(compositeId) }

  suspend fun clearRemovedModels(providerId: String) =
    withContext(dispatchers.io) {
      val existing = aiModelDao.getForProvider(providerId)
      existing
        .filter { it.availabilityStatus == ModelAvailabilityStatus.REMOVED.name }
        .forEach { aiModelDao.deleteByCompositeId(it.compositeId) }
    }

  private fun AiModelEntity.toDomain(): AIModel =
    AIModel(
      compositeId = compositeId,
      providerId = providerId,
      modelId = modelId,
      displayName = displayName,
      contextWindow = contextWindow,
      maxOutputTokens = maxOutputTokens,
      inputModalities = inputModalities,
      outputModalities = outputModalities,
      visionCapability = CapabilitySupport.entries.firstOrNull { it.name == supportsVision } ?: CapabilitySupport.UNKNOWN,
      toolCallingCapability =
        CapabilitySupport.entries.firstOrNull { it.name == supportsToolCalling } ?: CapabilitySupport.UNKNOWN,
      streamingCapability =
        CapabilitySupport.entries.firstOrNull { it.name == supportsStreaming } ?: CapabilitySupport.UNKNOWN,
      availabilityStatus =
        ModelAvailabilityStatus.entries.firstOrNull { it.name == availabilityStatus } ?: ModelAvailabilityStatus.DISCOVERED,
      isManual = isManual,
      lastVerifiedAtMillis = lastVerifiedAtMillis,
    )

  private fun AIModel.toEntity(now: Long): AiModelEntity =
    AiModelEntity(
      compositeId = compositeId,
      providerId = providerId,
      modelId = modelId,
      displayName = displayName,
      contextWindow = contextWindow,
      maxOutputTokens = maxOutputTokens,
      inputModalities = inputModalities,
      outputModalities = outputModalities,
      supportsVision = visionCapability.name,
      supportsToolCalling = toolCallingCapability.name,
      supportsStreaming = streamingCapability.name,
      availabilityStatus = availabilityStatus.name,
      isManual = isManual,
      lastVerifiedAtMillis = lastVerifiedAtMillis,
      updatedAtMillis = now,
    )
}
