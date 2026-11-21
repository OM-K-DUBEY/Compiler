package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiStreamEvent
import com.example.ai.OnlineAiClient
import com.example.ai.provider.AIModel
import com.example.ai.provider.AIModelRegistry
import com.example.ai.provider.AIProviderRegistry
import com.example.ai.provider.AITransport
import com.example.ai.provider.AuthMethod
import com.example.ai.provider.CapabilitySupport
import com.example.ai.provider.ConnectionStatus
import com.example.ai.provider.ErrorCategory
import com.example.ai.provider.ModelAvailabilityStatus
import com.example.ai.provider.ProviderUrlValidator
import com.example.core.common.AppDispatchers
import com.example.core.common.RedactingLogger
import com.example.core.database.DevForgeDatabase
import com.example.core.preferences.OnlineProviderType
import com.example.core.preferences.PreferencesRepository
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiProviderAndModelTest {

  private lateinit var context: Context
  private lateinit var db: DevForgeDatabase
  private lateinit var cipher: JvmAesTestSecretCipher
  private lateinit var prefsRepo: PreferencesRepository
  private lateinit var providerRegistry: AIProviderRegistry
  private lateinit var modelRegistry: AIModelRegistry
  private lateinit var onlineAiClient: OnlineAiClient
  private lateinit var server: HttpServer
  private var baseHttpUrl: String = ""

  @Volatile private var testStatusOverride: Int = 200
  @Volatile private var testBodyOverride: String = ""
  @Volatile private var geminiModelsJson: String = """{"models":[{"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","inputTokenLimit":1048576,"outputTokenLimit":8192,"supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro"}]}"""
  @Volatile private var openAiModelsJson: String = """{"data":[{"id":"gpt-4o"},{"id":"o3-mini"}]}"""

  private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, DevForgeDatabase::class.java).allowMainThreadQueries().build()
    cipher = JvmAesTestSecretCipher()
    prefsRepo = PreferencesRepository(context, cipher)
    val okHttp = OkHttpClient()
    val transport = AITransport(okHttp, dispatchers)
    providerRegistry = AIProviderRegistry(db.aiProviderDao(), db.aiModelDao(), cipher, prefsRepo, transport, dispatchers)
    modelRegistry = AIModelRegistry(db.aiModelDao(), providerRegistry, dispatchers)
    onlineAiClient = OnlineAiClient(providerRegistry, dispatchers)

    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
      val path = ex.requestURI.path
      val status = testStatusOverride
      val respBody: String
      var contentType = "application/json"

      if (status != 200) {
        respBody = testBodyOverride.ifBlank { """{"error":{"message":"Simulated HTTP $status"}}""" }
        val bytes = respBody.toByteArray()
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
        return@createContext
      }

      when {
        path.endsWith("/models") && ex.requestHeaders.containsKey("x-goog-api-key") -> {
          respBody = geminiModelsJson
        }
        path.endsWith("/models") -> {
          respBody = openAiModelsJson
        }
        path.endsWith("/messages") -> {
          respBody = """{"content":[{"type":"text","text":"Claude verified response"}],"usage":{"input_tokens":5,"output_tokens":3}}"""
        }
        path.endsWith("/chat/completions") -> {
          val reqText = ex.requestBody.bufferedReader().readText()
          if (reqText.contains("\"stream\":true")) {
            contentType = "text/event-stream"
            respBody =
              "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"World\"}}]}\n\n" +
                "data: [DONE]\n\n"
          } else {
            respBody = """{"choices":[{"message":{"content":"Verified OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2}}"""
          }
        }
        else -> {
          respBody = """{"candidates":[{"content":{"parts":[{"text":"OK"}]},"finishReason":"STOP"}]}"""
        }
      }

      val bytes = respBody.toByteArray()
      ex.responseHeaders.add("Content-Type", contentType)
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    baseHttpUrl = "http://127.0.0.1:${server.address.port}/v1"
  }

  @After
  fun tearDown() {
    server.stop(0)
    db.close()
  }

  @Test
  fun urlValidatorAllowsHttpsAndLocalDevHttpAndBlocksPublicHttp() {
    assertTrue(ProviderUrlValidator.validate("https://api.openai.com/v1").isValid)
    val local = ProviderUrlValidator.validate("http://127.0.0.1:11434/v1")
    assertTrue(local.isValid)
    assertTrue(!local.securityWarning.isNullOrBlank())

    val publicHttp = ProviderUrlValidator.validate("http://api.openai.com/v1")
    assertFalse(publicHttp.isValid)
  }

  @Test
  fun providerCrudEncryptsKeyAndSupportsTestConnectionErrorCategories() = runBlocking {
    val secretKey = "sk-test-secret-key-99999"
    val saved =
      providerRegistry.saveProvider(
        displayName = "Local OpenAI",
        providerType = OnlineProviderType.CUSTOM_OPENAI,
        baseUrl = baseHttpUrl,
        newPlainApiKey = secretKey,
        defaultModelId = "gpt-4o",
      ).getOrThrow()

    assertNotEquals(secretKey, saved.encryptedApiKey)
    assertEquals(secretKey, providerRegistry.decryptKey(saved.encryptedApiKey))

    // 200 OK
    testStatusOverride = 200
    val okRes = providerRegistry.testConnection(saved.id)
    assertTrue(okRes.success)
    assertEquals(ConnectionStatus.CONNECTED, providerRegistry.getProvider(saved.id)?.lastConnectionStatus)

    // 401 Unauthorized
    testStatusOverride = 401
    assertEquals(ErrorCategory.AUTH_FAILED, providerRegistry.testConnection(saved.id).errorCategory)
    // 403 Forbidden
    testStatusOverride = 403
    assertEquals(ErrorCategory.PERMISSION_DENIED, providerRegistry.testConnection(saved.id).errorCategory)
    // 404 Wrong endpoint
    testStatusOverride = 404
    assertEquals(ErrorCategory.NOT_FOUND, providerRegistry.testConnection(saved.id).errorCategory)
    // 429 Rate limited
    testStatusOverride = 429
    assertEquals(ErrorCategory.RATE_LIMITED, providerRegistry.testConnection(saved.id).errorCategory)
    // 500 Server error
    testStatusOverride = 500
    assertEquals(ErrorCategory.SERVER_ERROR, providerRegistry.testConnection(saved.id).errorCategory)

    // Clear API key
    providerRegistry.clearProviderApiKey(saved.id)
    assertFalse(providerRegistry.getProvider(saved.id)!!.hasApiKey)

    // Delete provider
    providerRegistry.deleteProvider(saved.id)
    assertEquals(null, providerRegistry.getProvider(saved.id))
  }

  @Test
  fun modelDiscoveryDetectsRemovedModelsAndManualModelPreservesUnknownCapabilities() = runBlocking {
    val openai =
      providerRegistry.saveProvider(
        displayName = "Mock OpenAI",
        providerType = OnlineProviderType.OPENAI,
        baseUrl = baseHttpUrl,
        newPlainApiKey = "sk-1234",
        defaultModelId = "gpt-4o",
      ).getOrThrow()

    testStatusOverride = 200
    val first = modelRegistry.refreshModels(openai.id).getOrThrow()
    assertEquals(2, first.size)
    assertTrue(first.any { it.modelId == "gpt-4o" })

    // Verify model status becomes VERIFIED
    val verifyRes = modelRegistry.verifyModel(AIModel.makeCompositeId(openai.id, "gpt-4o"))
    assertTrue(verifyRes.success)
    assertEquals(ModelAvailabilityStatus.VERIFIED, modelRegistry.getModelByCompositeId(AIModel.makeCompositeId(openai.id, "gpt-4o"))?.availabilityStatus)

    // Disappear from remote list -> marks as REMOVED
    openAiModelsJson = """{"data":[{"id":"gpt-4o"}]}"""
    val second = modelRegistry.refreshModels(openai.id).getOrThrow()
    val o3 = second.first { it.modelId == "o3-mini" }
    assertEquals(ModelAvailabilityStatus.REMOVED, o3.availabilityStatus)

    // Manual model preserves UNKNOWN capabilities by default
    val manual =
      modelRegistry.addOrUpdateManualModel(
        providerId = openai.id,
        modelId = "custom-finetune-v1",
      ).getOrThrow()
    assertEquals(CapabilitySupport.UNKNOWN, manual.visionCapability)
    assertEquals(CapabilitySupport.UNKNOWN, manual.toolCallingCapability)
    assertEquals(ModelAvailabilityStatus.MANUAL, manual.availabilityStatus)

    // Switching conversation / default / agent models
    modelRegistry.setConversationModel("proj1::default", manual.compositeId)
    assertEquals(manual.compositeId, modelRegistry.getConversationModel("proj1::default"))

    prefsRepo.setDefaultProviderAndModels(openai.id, manual.compositeId, AIModel.makeCompositeId(openai.id, "gpt-4o"))
    val p = prefsRepo.preferences.first()
    assertEquals(manual.compositeId, p.defaultChatModelCompositeId)
    assertEquals(AIModel.makeCompositeId(openai.id, "gpt-4o"), p.agentModelCompositeId)
  }

  @Test
  fun streamingAccumulatesTokensAndRedactsApiKeyInLogs() = runBlocking {
    val secret = "sk-live-supersecret-987654321"
    val prov =
      providerRegistry.saveProvider(
        displayName = "StreamProvider",
        providerType = OnlineProviderType.CUSTOM_OPENAI,
        baseUrl = baseHttpUrl,
        newPlainApiKey = secret,
        defaultModelId = "gpt-4o",
        authMethod = AuthMethod.BEARER,
        supportsStreaming = true,
      ).getOrThrow()

    val events =
      onlineAiClient.streamWithProvider(
        config = prov,
        modelId = "gpt-4o",
        systemPrompt = "You are concise.",
        messages = listOf(com.example.ai.provider.AIMessage("user", "Say hi")),
      ).toList()

    val fullText = events.filterIsInstance<AiStreamEvent.Token>().joinToString("") { it.text }
    assertEquals("Hello World", fullText)
    assertTrue(events.any { it is AiStreamEvent.Completed })

    val redacted = RedactingLogger.redact("Auth: Bearer $secret failed", listOf(secret))
    assertFalse(redacted.contains(secret))
    assertTrue(redacted.contains("[REDACTED]"))
  }

  @Test
  fun geminiAndAnthropicModelDiscoveryAndVerificationWorkAgainstSpecPayloads() = runBlocking {
    val gemini =
      providerRegistry.saveProvider(
        displayName = "Gemini Mock",
        providerType = OnlineProviderType.GEMINI,
        baseUrl = baseHttpUrl,
        newPlainApiKey = "AIzaMockTestKey1234567890",
        defaultModelId = "gemini-2.5-flash",
      ).getOrThrow()
    val gemModels = modelRegistry.refreshModels(gemini.id).getOrThrow()
    assertTrue(gemModels.any { it.modelId == "gemini-2.5-flash" && it.contextWindow == 1048576 })

    val claude =
      providerRegistry.saveProvider(
        displayName = "Claude Mock",
        providerType = OnlineProviderType.ANTHROPIC,
        baseUrl = baseHttpUrl,
        newPlainApiKey = "sk-ant-mock-1234567",
        defaultModelId = "claude-3-5-sonnet-latest",
      ).getOrThrow()
    val verifyClaude = modelRegistry.verifyModel(
      modelRegistry.addOrUpdateManualModel(claude.id, "claude-3-5-sonnet-latest").getOrThrow().compositeId
    )
    assertTrue(verifyClaude.success)
  }
}
