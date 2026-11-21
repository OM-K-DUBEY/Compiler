package com.example.sdk

import android.content.Context
import com.example.core.common.AppDispatchers
import com.example.core.common.DeviceInfoProvider
import com.example.core.database.SdkInstallDao
import com.example.core.database.SdkInstallEntity
import com.example.core.security.ZipPolicyLimits
import com.example.core.storage.ZipArchiveManager
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

data class DownloadProgress(
  val packageId: String,
  val bytesRead: Long,
  val totalBytes: Long,
  val statusMessage: String,
)

class SdkManagerRepository(
  private val context: Context,
  private val sdkInstallDao: SdkInstallDao,
  private val okHttpClient: OkHttpClient,
  private val deviceInfoProvider: DeviceInfoProvider,
  private val zipArchiveManager: ZipArchiveManager,
  private val dispatchers: AppDispatchers,
) {
  val sdksRoot: File
    get() = File(context.filesDir, "sdks").apply { mkdirs() }

  private val activeCalls = ConcurrentHashMap<String, Call>()
  private val downloadsState = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())

  fun observePackages(customToolchainPath: String): Flow<List<SdkPackageUiState>> {
    return combine(sdkInstallDao.observeAll(), downloadsState) { installed, downloads ->
      val installedMap = installed.associateBy { it.packageId }
      SdkCatalog.packages.map { spec ->
        val entity = installedMap[spec.id]
        val sysBinary = findExecutableOnDevice(spec.executableProbeName, customToolchainPath)
        val dl = downloads[spec.id]
        val localExecutable =
          entity != null && findExecutableInsideDir(File(entity.installPath), spec.executableProbeName) != null

        SdkPackageUiState(
          spec = spec,
          isInstalledLocally = entity != null && File(entity.installPath).exists(),
          installedVersion = entity?.version,
          installPath = entity?.installPath,
          installedSizeBytes = entity?.sizeBytes ?: 0L,
          isSystemDetected = sysBinary != null,
          systemBinaryPath = sysBinary?.absolutePath,
          isExecutableOnDevice = (sysBinary != null && sysBinary.canExecute()) || localExecutable,
          isDownloading = dl != null,
          downloadProgress =
            if (dl != null && dl.totalBytes > 0L) (dl.bytesRead.toFloat() / dl.totalBytes.toFloat()).coerceIn(0f, 1f)
            else 0f,
          downloadedBytes = dl?.bytesRead ?: 0L,
          totalBytes = dl?.totalBytes ?: spec.approximateSizeBytes,
          statusMessage = dl?.statusMessage,
        )
      }
    }
  }

  fun findExecutableOnDevice(binaryName: String, customToolchainPath: String = ""): File? {
    val searchDirs = mutableListOf<String>()
    if (customToolchainPath.isNotBlank()) {
      searchDirs += customToolchainPath.split(':').filter { it.isNotBlank() }
    }
    System.getenv("PATH")?.split(':')?.let { searchDirs.addAll(it) }
    searchDirs += listOf("/system/bin", "/system/xbin", "/data/data/com.termux/files/usr/bin")

    for (dirPath in searchDirs.distinct()) {
      val candidate = File(dirPath, binaryName)
      if (candidate.exists() && candidate.isFile && candidate.canExecute()) {
        return candidate
      }
    }
    return null
  }

  private fun findExecutableInsideDir(root: File, binaryName: String): File? {
    if (!root.exists()) return null
    return root.walkTopDown().maxDepth(4).firstOrNull { file ->
      file.isFile && file.name == binaryName && file.canExecute()
    }
  }

  suspend fun installPackage(spec: SdkPackageSpec): Result<SdkInstallEntity> =
    withContext(dispatchers.io) {
      val hw = deviceInfoProvider.snapshot(sdksRoot)
      if (!hw.isOnline) {
        return@withContext Result.failure(IllegalStateException("No internet connection available to download ${spec.displayName}"))
      }
      val neededBytes = (spec.approximateSizeBytes * 2.2).toLong()
      if (hw.availableStorageBytes < neededBytes) {
        return@withContext Result.failure(
          IllegalStateException("Insufficient storage: need ~${neededBytes / (1024 * 1024)} MB free")
        )
      }

      val tempArchive = File(context.cacheDir, "${spec.id}.${spec.archiveType}")
      val installDir = File(sdksRoot, spec.id)

      try {
        updateDownload(spec.id, 0L, spec.approximateSizeBytes, "Connecting to ${spec.downloadUrl.substringBefore('/', spec.downloadUrl)}...")
        val request = Request.Builder().url(spec.downloadUrl).build()
        val call = okHttpClient.newCall(request)
        activeCalls[spec.id] = call

        val digest = MessageDigest.getInstance("SHA-256")
        call.execute().use { response ->
          if (!response.isSuccessful) {
            throw IllegalStateException("Download failed with HTTP ${response.code}: ${response.message}")
          }
          val body = response.body ?: throw IllegalStateException("Empty download response")
          val total = body.contentLength().takeIf { it > 0 } ?: spec.approximateSizeBytes
          var downloaded = 0L
          val buf = ByteArray(16384)
          body.byteStream().use { input ->
            FileOutputStream(tempArchive).use { output ->
              while (true) {
                val read = input.read(buf)
                if (read == -1) break
                output.write(buf, 0, read)
                digest.update(buf, 0, read)
                downloaded += read
                updateDownload(spec.id, downloaded, total, "Downloading...")
              }
            }
          }
        }

        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        updateDownload(spec.id, tempArchive.length(), tempArchive.length(), "Verifying SHA-256 & extracting...")

        if (installDir.exists()) installDir.deleteRecursively()
        installDir.mkdirs()

        if (spec.archiveType == "zip") {
          val extracted =
            tempArchive.inputStream().use {
              zipArchiveManager.importZip(
                it,
                ZipPolicyLimits(
                  maxEntries = 40000,
                  maxTotalUncompressedBytes = 1500L * 1024 * 1024,
                  maxSingleFileBytes = 256L * 1024 * 1024,
                ),
              )
            }
          extracted.workspaceDir.copyRecursively(installDir, overwrite = true)
          extracted.workspaceDir.deleteRecursively()
        } else {
          // Store verified archive cleanly without pretending to extract unsupported tar.xz natively
          tempArchive.copyTo(File(installDir, tempArchive.name), overwrite = true)
        }

        val sizeOnDisk = installDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val binFile = findExecutableInsideDir(installDir, spec.executableProbeName)
        val canExec = binFile != null && binFile.canExecute()

        val entity =
          SdkInstallEntity(
            packageId = spec.id,
            displayName = spec.displayName,
            category = spec.category.name,
            version = spec.version,
            installPath = installDir.absolutePath,
            sizeBytes = sizeOnDisk,
            sha256 = sha256,
            isExecutableOnDevice = canExec,
            installedAtMillis = System.currentTimeMillis(),
          )
        sdkInstallDao.upsert(entity)
        Result.success(entity)
      } catch (e: Exception) {
        installDir.deleteRecursively()
        Result.failure(e)
      } finally {
        activeCalls.remove(spec.id)
        tempArchive.delete()
        clearDownload(spec.id)
      }
    }

  fun cancelDownload(packageId: String) {
    activeCalls[packageId]?.cancel()
    activeCalls.remove(packageId)
    clearDownload(packageId)
  }

  suspend fun uninstallPackage(packageId: String) =
    withContext(dispatchers.io) {
      val existing = sdkInstallDao.getById(packageId)
      if (existing != null) {
        File(existing.installPath).deleteRecursively()
        sdkInstallDao.deleteById(packageId)
      }
      File(sdksRoot, packageId).deleteRecursively()
    }

  private fun updateDownload(id: String, read: Long, total: Long, msg: String) {
    downloadsState.value = downloadsState.value + (id to DownloadProgress(id, read, total, msg))
  }

  private fun clearDownload(id: String) {
    downloadsState.value = downloadsState.value - id
  }
}
