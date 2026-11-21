package com.example.protection

import android.content.Context
import com.example.core.common.AppDispatchers
import com.example.core.database.MappingVaultDao
import com.example.core.database.MappingVaultEntity
import com.example.core.security.SecretCipher
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SymbolAndMappingVault(
  private val context: Context,
  private val mappingVaultDao: MappingVaultDao,
  private val secretCipher: SecretCipher,
  private val dispatchers: AppDispatchers,
) {
  val vaultDir: File
    get() = File(context.filesDir, "mapping_vault").apply { mkdirs() }

  fun observeAll(): Flow<List<MappingVaultEntity>> = mappingVaultDao.observeAll()

  fun observeForProject(projectId: String): Flow<List<MappingVaultEntity>> =
    mappingVaultDao.observeForProject(projectId)

  suspend fun storeMappingFile(
    projectId: String,
    buildId: Long,
    versionName: String,
    applicationId: String,
    toolName: String,
    toolVersion: String,
    buildVariant: String,
    artifactType: MappingArtifactType,
    sourceFile: File,
  ): Result<MappingVaultEntity> =
    withContext(dispatchers.io) {
      if (!sourceFile.exists() || !sourceFile.isFile) {
        return@withContext Result.failure(IllegalArgumentException("Mapping file missing: ${sourceFile.absolutePath}"))
      }
      val rawBytes = sourceFile.readBytes()
      val sha256 =
        MessageDigest.getInstance("SHA-256").digest(rawBytes).joinToString("") { "%02x".format(it) }
      val plainText = rawBytes.toString(Charsets.UTF_8)
      val encryptedText = secretCipher.encrypt(plainText)

      val id = UUID.randomUUID().toString()
      val archiveFile = File(vaultDir, "$id.vault.enc")
      archiveFile.writeText(encryptedText, Charsets.UTF_8)

      val entity =
        MappingVaultEntity(
          id = id,
          projectId = projectId,
          buildId = buildId,
          versionName = versionName.ifBlank { "1.0" },
          applicationId = applicationId,
          toolName = toolName,
          toolVersion = toolVersion,
          buildVariant = buildVariant,
          artifactType = artifactType.name,
          originalFileName = sourceFile.name,
          encryptedArchivePath = archiveFile.absolutePath,
          sha256 = sha256,
          sizeBytes = rawBytes.size.toLong(),
          createdAtMillis = System.currentTimeMillis(),
        )
      mappingVaultDao.upsert(entity)
      Result.success(entity)
    }

  suspend fun readDecryptedMapping(id: String): Result<String> =
    withContext(dispatchers.io) {
      val entity =
        mappingVaultDao.getById(id)
          ?: return@withContext Result.failure(IllegalArgumentException("Vault entry not found."))
      val encFile = File(entity.encryptedArchivePath)
      if (!encFile.exists()) {
        return@withContext Result.failure(IllegalStateException("Encrypted archive file is missing on disk."))
      }
      val encrypted = encFile.readText(Charsets.UTF_8)
      val decrypted = secretCipher.decrypt(encrypted)
      if (decrypted.isEmpty() && entity.sizeBytes > 0) {
        return@withContext Result.failure(SecurityException("Failed to decrypt mapping archive with Android Keystore."))
      }
      Result.success(decrypted)
    }

  suspend fun deleteEntry(id: String) =
    withContext(dispatchers.io) {
      val entity = mappingVaultDao.getById(id)
      if (entity != null) {
        File(entity.encryptedArchivePath).delete()
      }
      mappingVaultDao.deleteById(id)
    }
}
