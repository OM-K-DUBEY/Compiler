package com.example.compiler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.DevForgeApp
import java.util.UUID
import kotlinx.coroutines.flow.first

class BuildWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val container = (applicationContext as? DevForgeApp)?.container ?: return Result.failure()
    val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
    val systemName = inputData.getString(KEY_BUILD_SYSTEM) ?: return Result.failure()
    val variantName = inputData.getString(KEY_BUILD_VARIANT) ?: BuildVariant.DEBUG.name
    val signRelease = inputData.getBoolean(KEY_SIGN_RELEASE, true)

    val project = container.database.projectDao().getById(projectId) ?: return Result.failure()
    val system = BuildSystemType.entries.firstOrNull { it.name == systemName } ?: return Result.failure()
    val variant = BuildVariant.entries.firstOrNull { it.name == variantName } ?: BuildVariant.DEBUG
    val prefs = container.preferencesRepository.preferences.first()

    val res =
      container.buildManager.runBuild(
        project = project,
        buildSystem = system,
        variant = variant,
        customToolchainPath = prefs.customToolchainPath,
        signReleaseArtifact = signRelease,
      )

    val out =
      Data.Builder()
        .putString(KEY_RESULT_STATUS, res.status.name)
        .putInt(KEY_ERROR_COUNT, res.errorCount)
        .putString(KEY_ARTIFACT_PATH, res.artifacts.firstOrNull()?.path.orEmpty())
        .build()

    return when (res.status) {
      BuildStatus.SUCCEEDED, BuildStatus.UNAVAILABLE -> Result.success(out)
      BuildStatus.FAILED, BuildStatus.CANCELLED -> Result.failure(out)
      else -> Result.success(out)
    }
  }

  companion object {
    const val KEY_PROJECT_ID = "project_id"
    const val KEY_BUILD_SYSTEM = "build_system"
    const val KEY_BUILD_VARIANT = "build_variant"
    const val KEY_SIGN_RELEASE = "sign_release"
    const val KEY_RESULT_STATUS = "result_status"
    const val KEY_ERROR_COUNT = "error_count"
    const val KEY_ARTIFACT_PATH = "artifact_path"

    fun enqueue(
      context: Context,
      projectId: String,
      buildSystem: BuildSystemType,
      variant: BuildVariant,
      signRelease: Boolean,
    ): UUID {
      val req =
        OneTimeWorkRequestBuilder<BuildWorker>()
          .setInputData(
            Data.Builder()
              .putString(KEY_PROJECT_ID, projectId)
              .putString(KEY_BUILD_SYSTEM, buildSystem.name)
              .putString(KEY_BUILD_VARIANT, variant.name)
              .putBoolean(KEY_SIGN_RELEASE, signRelease)
              .build()
          )
          .addTag("devforge_build_$projectId")
          .build()
      WorkManager.getInstance(context.applicationContext).enqueue(req)
      return req.id
    }
  }
}
