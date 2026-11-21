package com.example.di

import android.content.Context
import com.example.ai.CodingAgentEngine
import com.example.ai.OnlineAiClient
import com.example.ai.ProjectContextManager
import com.example.compiler.BuildManager
import com.example.core.common.AppDispatchers
import com.example.core.common.DeviceInfoProvider
import com.example.core.database.DevForgeDatabase
import com.example.core.preferences.PreferencesRepository
import com.example.core.security.AndroidKeyStoreSecretManager
import com.example.core.security.KeystoreSigningManager
import com.example.core.storage.ProjectWorkspaceManager
import com.example.core.storage.ZipArchiveManager
import com.example.sdk.SdkManagerRepository
import com.example.terminal.TerminalSessionManager
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
  val appContext: Context = context.applicationContext
  val dispatchers = AppDispatchers()
  val deviceInfoProvider = DeviceInfoProvider(appContext)
  val secretManager = AndroidKeyStoreSecretManager()
  val signingManager = KeystoreSigningManager()

  val database: DevForgeDatabase = DevForgeDatabase.create(appContext)
  val preferencesRepository = PreferencesRepository(appContext, secretManager)

  val okHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.SECONDS) // Required for SSE streaming
      .writeTimeout(30, TimeUnit.SECONDS)
      .build()

  val workspaceManager =
    ProjectWorkspaceManager(
      context = appContext,
      projectDao = database.projectDao(),
      dispatchers = dispatchers,
    )

  val zipArchiveManager = ZipArchiveManager(workspaceManager, dispatchers)

  val sdkManagerRepository =
    SdkManagerRepository(
      context = appContext,
      sdkInstallDao = database.sdkInstallDao(),
      okHttpClient = okHttpClient,
      deviceInfoProvider = deviceInfoProvider,
      zipArchiveManager = zipArchiveManager,
      dispatchers = dispatchers,
    )

  val buildManager =
    BuildManager(
      buildHistoryDao = database.buildHistoryDao(),
      sdkManagerRepository = sdkManagerRepository,
      zipArchiveManager = zipArchiveManager,
      signingManager = signingManager,
      dispatchers = dispatchers,
    )

  val terminalSessionManager =
    TerminalSessionManager(
      initialDir = workspaceManager.workspacesRoot,
      dispatchers = dispatchers,
    )

  val onlineAiClient = OnlineAiClient(okHttpClient, dispatchers)
  val projectContextManager = ProjectContextManager(workspaceManager)
  val codingAgentEngine =
    CodingAgentEngine(
      workspaceManager = workspaceManager,
      checkpointDao = database.checkpointDao(),
      dispatchers = dispatchers,
    )
}
