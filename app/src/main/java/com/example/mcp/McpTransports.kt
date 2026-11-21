package com.example.mcp

import com.example.core.common.RedactingLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

interface McpTransportSession {
  val negotiatedProtocolVersion: String
  suspend fun initialize(): JSONObject
  suspend fun sendNotification(method: String, params: JSONObject = JSONObject())
  suspend fun sendRequest(method: String, params: JSONObject = JSONObject()): JSONObject
  fun close()
}

/**
 * Streamable HTTP MCP transport (2025-03-26 spec):
 * - POST JSON-RPC requests to the exact endpoint URL
 * - Persists `Mcp-Session-Id` across requests
 * - Parses either `application/json` or `text/event-stream` HTTP response bodies
 * - Sends HTTP DELETE on close if a session ID was established
 */
class StreamableHttpMcpSession(
  private val config: McpServerConfig,
  private val plainToken: String,
  private val baseClient: OkHttpClient,
  private val onLog: (String) -> Unit,
) : McpTransportSession {

  private val idCounter = AtomicLong(1L)
  @Volatile private var sessionId: String? = null
  @Volatile override var negotiatedProtocolVersion: String = McpSecurityPolicy.PREFERRED_PROTOCOL_VERSION
    private set

  private val client =
    baseClient.newBuilder()
      .connectTimeout(config.timeoutSeconds.coerceIn(5, 180).toLong(), TimeUnit.SECONDS)
      .readTimeout(config.timeoutSeconds.coerceIn(5, 180).toLong(), TimeUnit.SECONDS)
      .writeTimeout(config.timeoutSeconds.coerceIn(5, 180).toLong(), TimeUnit.SECONDS)
      .build()

  override suspend fun initialize(): JSONObject {
    val params =
      JSONObject().apply {
        put("protocolVersion", McpSecurityPolicy.PREFERRED_PROTOCOL_VERSION)
        put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
        put("clientInfo", JSONObject().put("name", "DevForge AI").put("version", "2.0.0"))
      }
    val res = sendRequest("initialize", params)
    val resultObj = res.optJSONObject("result")
      ?: throw IllegalStateException("MCP initialize response missing 'result' object.")
    val serverVersion = resultObj.optString("protocolVersion", "").trim()
    if (serverVersion.isEmpty() || serverVersion !in McpSecurityPolicy.SUPPORTED_PROTOCOL_VERSIONS) {
      throw IllegalStateException(
        "MCP protocol version mismatch: server returned '${serverVersion.ifEmpty { "<empty>" }}', supported versions are ${McpSecurityPolicy.SUPPORTED_PROTOCOL_VERSIONS.joinToString()}"
      )
    }
    negotiatedProtocolVersion = serverVersion
    return resultObj
  }

  override suspend fun sendNotification(method: String, params: JSONObject) {
    val body =
      JSONObject().apply {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params.length() > 0) put("params", params)
      }
    postJsonRpc(body, expectResponse = false)
  }

  override suspend fun sendRequest(method: String, params: JSONObject): JSONObject {
    val reqId = idCounter.getAndIncrement()
    val body =
      JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", reqId)
        put("method", method)
        if (params.length() > 0) put("params", params)
      }
    val raw = postJsonRpc(body, expectResponse = true)
      ?: throw IllegalStateException("Empty response from MCP server for method '$method'.")
    if (raw.has("error") && !raw.isNull("error")) {
      val err = raw.getJSONObject("error")
      val code = err.optInt("code", -1)
      val msg = RedactingLogger.redact(err.optString("message", "Unknown JSON-RPC error"), listOf(plainToken))
      throw IllegalStateException("MCP error $code: $msg")
    }
    val respId = raw.opt("id")?.toString()
    if (respId != null && respId != reqId.toString()) {
      throw IllegalStateException("MCP JSON-RPC ID mismatch: expected $reqId, got $respId")
    }
    return raw
  }

  private fun postJsonRpc(payload: JSONObject, expectResponse: Boolean): JSONObject? {
    val builder =
      Request.Builder()
        .url(config.endpointUrl.trim())
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", negotiatedProtocolVersion)
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

    sessionId?.let { builder.header("Mcp-Session-Id", it) }
    applyAuthHeaders(builder, config, plainToken)

    onLog("-> ${payload.optString("method")} (id=${payload.opt("id") ?: "notification"})")
    client.newCall(builder.build()).execute().use { response ->
      response.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
      if (!response.isSuccessful && !(response.code == 202 && !expectResponse)) {
        val errBody = RedactingLogger.redact(response.body?.string()?.take(500).orEmpty(), listOf(plainToken))
        throw IllegalStateException("HTTP ${response.code} from MCP server: ${errBody.ifBlank { response.message }}")
      }
      if (!expectResponse || response.code == 202 || response.code == 204) {
        return null
      }
      val bodyStr = readBoundedBody(response.body?.byteStream())
      if (bodyStr.isBlank()) return null

      val contentType = response.header("Content-Type").orEmpty().lowercase()
      return if (contentType.contains("text/event-stream") || bodyStr.trimStart().startsWith("data:") || bodyStr.trimStart().startsWith("event:")) {
        extractJsonFromSse(bodyStr, payload.opt("id")?.toString())
      } else {
        JSONObject(bodyStr)
      }
    }
  }

  private fun extractJsonFromSse(sseText: String, targetId: String?): JSONObject {
    val payloads = mutableListOf<String>()
    val buf = StringBuilder()
    sseText.lineSequence().forEach { line ->
      when {
        line.startsWith("data:") -> {
          if (buf.isNotEmpty()) buf.append('\n')
          buf.append(line.removePrefix("data:").trim())
        }
        line.isBlank() && buf.isNotEmpty() -> {
          payloads += buf.toString()
          buf.clear()
        }
      }
    }
    if (buf.isNotEmpty()) payloads += buf.toString()

    val parsed = payloads.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    if (targetId != null) {
      parsed.firstOrNull { it.opt("id")?.toString() == targetId }?.let { return it }
    }
    return parsed.lastOrNull()
      ?: throw IllegalStateException("No valid JSON-RPC payload found in MCP SSE response.")
  }

  override fun close() {
    val sid = sessionId ?: return
    sessionId = null
    runCatching {
      val builder = Request.Builder().url(config.endpointUrl.trim()).header("Mcp-Session-Id", sid).delete()
      applyAuthHeaders(builder, config, plainToken)
      client.newCall(builder.build()).execute().close()
    }
  }
}

/**
 * Legacy HTTP+SSE MCP transport (2024-11-05 spec):
 * - Opens a persistent GET SSE connection to discover the `endpoint` URI event
 * - Sends JSON-RPC POSTs to that discovered message endpoint
 * - Correlates asynchronous JSON-RPC `message` events by request `id`
 */
class LegacyHttpSseMcpSession(
  private val config: McpServerConfig,
  private val plainToken: String,
  private val baseClient: OkHttpClient,
  private val onLog: (String) -> Unit,
) : McpTransportSession {

  private val idCounter = AtomicLong(1L)
  private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
  private val endpointReady = CompletableDeferred<String>()
  @Volatile private var sseCall: Call? = null
  @Volatile private var readerThread: Thread? = null
  @Volatile private var closed = false
  @Volatile override var negotiatedProtocolVersion: String = "2024-11-05"
    private set

  private val sseClient =
    baseClient.newBuilder()
      .connectTimeout(config.timeoutSeconds.coerceIn(5, 180).toLong(), TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.SECONDS)
      .build()

  private val postClient =
    baseClient.newBuilder()
      .connectTimeout(config.timeoutSeconds.coerceIn(5, 180).toLong(), TimeUnit.SECONDS)
      .readTimeout(config.timeoutSeconds.coerceIn(5, 180).toLong(), TimeUnit.SECONDS)
      .build()

  private fun ensureSseLoopStarted() {
    if (sseCall != null) return
    val builder =
      Request.Builder()
        .url(config.endpointUrl.trim())
        .header("Accept", "text/event-stream")
    applyAuthHeaders(builder, config, plainToken)
    val call = sseClient.newCall(builder.build())
    sseCall = call
    val thread =
      Thread {
        try {
          call.execute().use { response ->
            if (!response.isSuccessful) {
              val msg = "HTTP ${response.code} opening legacy SSE stream"
              endpointReady.completeExceptionally(IllegalStateException(msg))
              failAllPending(IllegalStateException(msg))
              return@Thread
            }
            val body = response.body ?: run {
              endpointReady.completeExceptionally(IllegalStateException("Empty SSE body"))
              return@Thread
            }
            BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
              var eventName = "message"
              val dataBuf = StringBuilder()
              while (!closed) {
                val line = reader.readLine() ?: break
                when {
                  line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                  line.startsWith("data:") -> {
                    if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                    dataBuf.append(line.removePrefix("data:").trim())
                  }
                  line.isBlank() && dataBuf.isNotEmpty() -> {
                    val data = dataBuf.toString().trim()
                    dataBuf.clear()
                    handleSseEvent(eventName, data)
                    eventName = "message"
                  }
                }
              }
            }
          }
        } catch (e: Exception) {
          if (!closed) {
            endpointReady.completeExceptionally(e)
            failAllPending(e)
          }
        }
      }.apply {
        name = "mcp-sse-${config.id.take(8)}"
        isDaemon = true
      }
    readerThread = thread
    thread.start()
  }

  private fun handleSseEvent(eventName: String, data: String) {
    if (eventName == "endpoint") {
      val resolved =
        runCatching { URI(config.endpointUrl.trim()).resolve(data.trim()).toString() }
          .getOrElse { data.trim() }
      onLog("Discovered legacy SSE message endpoint: $resolved")
      endpointReady.complete(resolved)
      return
    }
    runCatching { JSONObject(data) }.onSuccess { json ->
      val id = json.opt("id")?.toString()
      if (id != null) {
        pending.remove(id)?.complete(json)
      } else {
        onLog("<- notification: ${json.optString("method", "unknown")}")
      }
    }
  }

  override suspend fun initialize(): JSONObject {
    ensureSseLoopStarted()
    val timeoutMs = config.timeoutSeconds.coerceIn(5, 180) * 1000L
    withTimeout(timeoutMs) { endpointReady.await() }
    val params =
      JSONObject().apply {
        put("protocolVersion", "2024-11-05")
        put("capabilities", JSONObject().put("tools", JSONObject()))
        put("clientInfo", JSONObject().put("name", "DevForge AI").put("version", "2.0.0"))
      }
    val res = sendRequest("initialize", params)
    val resultObj = res.optJSONObject("result")
      ?: throw IllegalStateException("MCP initialize response missing 'result'.")
    val version = resultObj.optString("protocolVersion", "").trim()
    if (version.isEmpty() || version !in McpSecurityPolicy.SUPPORTED_PROTOCOL_VERSIONS) {
      throw IllegalStateException("MCP protocol version mismatch: '$version'")
    }
    negotiatedProtocolVersion = version
    return resultObj
  }

  override suspend fun sendNotification(method: String, params: JSONObject) {
    val target = endpointReady.await()
    val payload =
      JSONObject().apply {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params.length() > 0) put("params", params)
      }
    postMessage(target, payload)
  }

  override suspend fun sendRequest(method: String, params: JSONObject): JSONObject {
    val target = endpointReady.await()
    val reqId = idCounter.getAndIncrement().toString()
    val deferred = CompletableDeferred<JSONObject>()
    pending[reqId] = deferred
    val payload =
      JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", reqId.toLong())
        put("method", method)
        if (params.length() > 0) put("params", params)
      }
    val immediate = postMessage(target, payload)
    if (immediate != null && immediate.has("id")) {
      pending.remove(reqId)
      deferred.complete(immediate)
    }
    val resp = withTimeout(config.timeoutSeconds.coerceIn(5, 180) * 1000L) { deferred.await() }
    if (resp.has("error") && !resp.isNull("error")) {
      val err = resp.getJSONObject("error")
      throw IllegalStateException("MCP error ${err.optInt("code", -1)}: ${err.optString("message", "Unknown")}")
    }
    return resp
  }

  private fun postMessage(url: String, payload: JSONObject): JSONObject? {
    val builder = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
    applyAuthHeaders(builder, config, plainToken)
    postClient.newCall(builder.build()).execute().use { resp ->
      if (!resp.isSuccessful && resp.code != 202) {
        throw IllegalStateException("HTTP ${resp.code} posting MCP message")
      }
      val body = readBoundedBody(resp.body?.byteStream())
      return if (body.trimStart().startsWith("{")) runCatching { JSONObject(body) }.getOrNull() else null
    }
  }

  private fun failAllPending(cause: Throwable) {
    pending.values.forEach { it.completeExceptionally(cause) }
    pending.clear()
  }

  override fun close() {
    closed = true
    sseCall?.cancel()
    sseCall = null
    failAllPending(IllegalStateException("MCP SSE session closed"))
  }
}

/**
 * Checks whether an Android STDIO binary is genuinely executable in the current environment.
 * On standard unrooted Android devices, arbitrary npm/npx/python/node STDIO MCP servers cannot execute;
 * this throws an honest diagnostic error unless a genuinely executable local binary was supplied.
 */
class StdioMcpSession(
  private val config: McpServerConfig,
  private val onLog: (String) -> Unit,
) : McpTransportSession {
  private val idCounter = AtomicLong(1L)
  private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
  @Volatile private var process: Process? = null
  @Volatile private var writer: BufferedWriter? = null
  @Volatile override var negotiatedProtocolVersion: String = McpSecurityPolicy.PREFERRED_PROTOCOL_VERSION
    private set

  private fun startProcessIfSupported() {
    if (process != null) return
    val parts = config.endpointUrl.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val exe = parts.firstOrNull()
      ?: throw UnsupportedOperationException("No STDIO executable specified.")
    val exeFile = resolveExecutable(exe)
      ?: throw UnsupportedOperationException(
        "STDIO MCP server '$exe' cannot run on this Android environment: executable not found on PATH or blocked by Android SELinux W^X sandbox. Use a remote Streamable HTTP MCP server instead."
      )
    val proc =
      try {
        ProcessBuilder(listOf(exeFile.absolutePath) + parts.drop(1)).start()
      } catch (e: Exception) {
        throw UnsupportedOperationException(
          "Unable to launch STDIO MCP server '$exe': ${e.message}. Android blocks spawning untrusted binaries; use Streamable HTTP MCP instead."
        )
      }
    process = proc
    writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
    Thread {
      BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
        while (true) {
          val line = reader.readLine() ?: break
          runCatching { JSONObject(line) }.onSuccess { json ->
            val id = json.opt("id")?.toString()
            if (id != null) pending.remove(id)?.complete(json)
          }
        }
      }
    }.apply {
      isDaemon = true
      start()
    }
  }

  private fun resolveExecutable(name: String): File? {
    val direct = File(name)
    if (direct.isAbsolute && direct.exists() && direct.canExecute()) return direct
    val pathDirs = System.getenv("PATH")?.split(':').orEmpty()
    for (d in pathDirs) {
      val f = File(d, name)
      if (f.exists() && f.isFile && f.canExecute()) return f
    }
    return null
  }

  override suspend fun initialize(): JSONObject {
    startProcessIfSupported()
    val res =
      sendRequest(
        "initialize",
        JSONObject()
          .put("protocolVersion", McpSecurityPolicy.PREFERRED_PROTOCOL_VERSION)
          .put("capabilities", JSONObject())
          .put("clientInfo", JSONObject().put("name", "DevForge AI").put("version", "2.0.0")),
      )
    val resultObj = res.optJSONObject("result")
      ?: throw IllegalStateException("STDIO MCP initialize response missing 'result'.")
    val ver = resultObj.optString("protocolVersion", "")
    if (ver !in McpSecurityPolicy.SUPPORTED_PROTOCOL_VERSIONS) {
      throw IllegalStateException("MCP protocol version mismatch: '$ver'")
    }
    negotiatedProtocolVersion = ver
    return resultObj
  }

  override suspend fun sendNotification(method: String, params: JSONObject) {
    val w = writer ?: return
    val msg = JSONObject().put("jsonrpc", "2.0").put("method", method)
    if (params.length() > 0) msg.put("params", params)
    synchronized(w) {
      w.write(msg.toString())
      w.newLine()
      w.flush()
    }
    onLog("-> STDIO notification $method")
  }

  override suspend fun sendRequest(method: String, params: JSONObject): JSONObject {
    val w = writer ?: throw IllegalStateException("STDIO process is not running")
    val reqId = idCounter.getAndIncrement()
    val deferred = CompletableDeferred<JSONObject>()
    pending[reqId.toString()] = deferred
    val msg = JSONObject().put("jsonrpc", "2.0").put("id", reqId).put("method", method)
    if (params.length() > 0) msg.put("params", params)
    synchronized(w) {
      w.write(msg.toString())
      w.newLine()
      w.flush()
    }
    val resp = withTimeout(config.timeoutSeconds.coerceIn(5, 180) * 1000L) { deferred.await() }
    if (resp.has("error") && !resp.isNull("error")) {
      val err = resp.getJSONObject("error")
      throw IllegalStateException("MCP error ${err.optInt("code", -1)}: ${err.optString("message", "Unknown")}")
    }
    return resp
  }

  override fun close() {
    process?.destroyForcibly()
    process = null
    writer = null
    pending.values.forEach { it.completeExceptionally(IllegalStateException("STDIO MCP closed")) }
    pending.clear()
  }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private fun applyAuthHeaders(builder: Request.Builder, config: McpServerConfig, plainToken: String) {
  when (config.authType) {
    McpAuthType.BEARER ->
      if (plainToken.isNotBlank()) builder.header("Authorization", "Bearer ${plainToken.trim()}")
    McpAuthType.CUSTOM_HEADER -> {
      val headerKey = config.customHeaders.keys.firstOrNull { it.equals("x-api-key", true) || it.equals("authorization", true) }
        ?: "Authorization"
      if (plainToken.isNotBlank()) builder.header(headerKey, plainToken.trim())
    }
    McpAuthType.NONE -> Unit
  }
  config.customHeaders.forEach { (k, v) ->
    val cleanK = k.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    val cleanV = v.replace("\r", "").replace("\n", "").trim()
    if (cleanK.isNotEmpty() && cleanV.isNotEmpty()) {
      builder.header(cleanK, cleanV)
    }
  }
}

private fun readBoundedBody(stream: java.io.InputStream?): String {
  if (stream == null) return ""
  val buf = ByteArray(8192)
  val out = java.io.ByteArrayOutputStream()
  var total = 0
  while (true) {
    val n = stream.read(buf)
    if (n == -1) break
    if (total + n > McpSecurityPolicy.MAX_RESPONSE_BYTES) {
      throw IllegalStateException("MCP server response exceeded ${McpSecurityPolicy.MAX_RESPONSE_BYTES / 1024} KB limit.")
    }
    out.write(buf, 0, n)
    total += n
  }
  return out.toString(Charsets.UTF_8.name())
}
