package com.example.ai

import android.content.Context
import com.example.core.common.AppDispatchers
import com.example.core.common.DeviceInfoProvider
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

data class OfflineDownloadState(
  val modelId: String,
  val bytesRead: Long,
  val totalBytes: Long,
)

class OfflineAiManager(
  private val context: Context,
  private val okHttpClient: OkHttpClient,
  private val deviceInfoProvider: DeviceInfoProvider,
  private val dispatchers: AppDispatchers,
) {
  val modelsDir: File
    get() = File(context.filesDir, "models").apply { mkdirs() }

  private val activeCalls = ConcurrentHashMap<String, Call>()

  private val _installedModels = MutableStateFlow<List<InstalledOfflineModel>>(emptyList())
  val installedModels: StateFlow<List<InstalledOfflineModel>> = _installedModels.asStateFlow()

  private val _downloads = MutableStateFlow<Map<String, OfflineDownloadState>>(emptyMap())
  val downloads: StateFlow<Map<String, OfflineDownloadState>> = _downloads.asStateFlow()

  val isNativeLlamaLoaded: Boolean by lazy {
    runCatching {
      System.loadLibrary("llama_jni")
      true
    }.getOrDefault(false)
  }

  fun refreshInstalledModels() {
    val list =
      modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
        ?.map { file ->
          val header = parseGgufHeader(file)
          InstalledOfflineModel(
            filePath = file.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            ggufHeader = header,
            isValidGguf = header != null,
          )
        }
        ?.sortedBy { it.fileName }
        ?: emptyList()
    _installedModels.value = list
  }

  fun checkCompatibility(spec: OfflineModelSpec): String? {
    val hw = deviceInfoProvider.snapshot(modelsDir)
    if (hw.totalRamBytes > 0 && hw.totalRamBytes < spec.minRamBytes) {
      return "Device RAM (${hw.totalRamBytes / (1024 * 1024)} MB) is below recommended ${spec.minRamBytes / (1024 * 1024)} MB"
    }
    if (hw.availableStorageBytes < (spec.sizeBytes * 1.15).toLong()) {
      return "Insufficient storage (${hw.availableStorageBytes / (1024 * 1024)} MB free)"
    }
    return null
  }

  suspend fun downloadModel(spec: OfflineModelSpec): Result<InstalledOfflineModel> =
    withContext(dispatchers.io) {
      val hw = deviceInfoProvider.snapshot(modelsDir)
      if (!hw.isOnline) {
        return@withContext Result.failure(IllegalStateException("Internet connection required to download GGUF model"))
      }
      if (hw.availableStorageBytes < (spec.sizeBytes * 1.1).toLong()) {
        return@withContext Result.failure(IllegalStateException("Insufficient storage space for ${spec.displayName}"))
      }

      val partFile = File(modelsDir, "${spec.fileName}.part")
      val finalFile = File(modelsDir, spec.fileName)

      try {
        val request = Request.Builder().url(spec.downloadUrl).build()
        val call = okHttpClient.newCall(request)
        activeCalls[spec.id] = call

        call.execute().use { response ->
          if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}: ${response.message}")
          }
          val body = response.body ?: throw IllegalStateException("Empty download response")
          val total = body.contentLength().takeIf { it > 0 } ?: spec.sizeBytes
          var downloaded = 0L
          val buf = ByteArray(16384)
          body.byteStream().use { input ->
            FileOutputStream(partFile).use { output ->
              while (true) {
                val read = input.read(buf)
                if (read == -1) break
                output.write(buf, 0, read)
                downloaded += read
                _downloads.value = _downloads.value + (spec.id to OfflineDownloadState(spec.id, downloaded, total))
              }
            }
          }
        }

        val header = parseGgufHeader(partFile)
          ?: throw IllegalStateException("Downloaded file failed GGUF magic header verification")

        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
          partFile.copyTo(finalFile, overwrite = true)
          partFile.delete()
        }
        refreshInstalledModels()
        Result.success(
          InstalledOfflineModel(
            filePath = finalFile.absolutePath,
            fileName = finalFile.name,
            sizeBytes = finalFile.length(),
            ggufHeader = header,
            isValidGguf = true,
          )
        )
      } catch (e: Exception) {
        partFile.delete()
        Result.failure(e)
      } finally {
        activeCalls.remove(spec.id)
        _downloads.value = _downloads.value - spec.id
      }
    }

  fun cancelDownload(modelId: String) {
    activeCalls[modelId]?.cancel()
    activeCalls.remove(modelId)
    _downloads.value = _downloads.value - modelId
  }

  suspend fun deleteModel(filePath: String) =
    withContext(dispatchers.io) {
      File(filePath).delete()
      refreshInstalledModels()
    }

  /**
   * Parses real GGUF v2/v3 header:
   * - bytes 0..3: 'G','G','U','F'
   * - bytes 4..7: uint32 little-endian version
   * - bytes 8..15: uint64 little-endian tensor_count
   * - bytes 16..23: uint64 little-endian metadata_kv_count
   */
  fun parseGgufHeader(file: File): GgufHeaderInfo? {
    if (!file.exists() || file.length() < 24L) return null
    return runCatching {
      RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(24)
        raf.readFully(header)
        if (header[0] != 'G'.code.toByte() ||
          header[1] != 'G'.code.toByte() ||
          header[2] != 'U'.code.toByte() ||
          header[3] != 'F'.code.toByte()
        ) {
          return null
        }
        val buf = ByteBuffer.wrap(header, 4, 20).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.int
        val tensorCount = buf.long
        val metadataKvCount = buf.long
        if (version !in 1..5) return null
        GgufHeaderInfo(version, tensorCount, metadataKvCount)
      }
    }.getOrNull()
  }
}
