# DevForge AI — Architecture, SDK & Build Documentation

## 1. Architecture Overview
DevForge AI is a local-first native Android IDE built with **Kotlin**, **Jetpack Compose (Material 3)**, **Room**, **DataStore**, **OkHttp**, and **AndroidKeyStore**.

### Package Structure
- `core/common`: Dispatchers, hardware/RAM/storage/network probes (`DeviceInfoProvider`), secret-redacting logger (`RedactingLogger`).
- `core/security`: `AndroidKeyStoreSecretManager` (AES-256-GCM), `PathSandbox` (canonical path containment), `ZipSecurityValidator` (path traversal & zip-bomb limits), `KeystoreSigningManager` (RSA-2048 manifest signing).
- `core/database`: Room `DevForgeDatabase` (`projects`, `build_history`, `ai_messages`, `checkpoints`, `editor_tabs`, `sdk_installs`).
- `core/preferences`: `PreferencesRepository` backed by Jetpack DataStore.
- `core/storage`: `ProjectWorkspaceManager`, `ProjectTemplates`, `ZipArchiveManager` (SAF ZIP import/export & SHA-256 verified backups).
- `editor`: `LanguageSupport`, `SyntaxHighlighter`, `DiagnosticsEngine`, `CompletionEngine`, `CodeFormatter`, `SearchEngine`, `DiffEngine`, `CodeFoldingEngine`.
- `compiler`: `ProjectDetector` & `BuildManager` (Web static bundling + WebView live runtime, static pre-flight diagnostics, and real `ProcessBuilder` toolchain execution with cancellation).
- `terminal`: `TerminalSessionManager` (`/system/bin/sh` process streaming, working directory, env vars, command history, script confirmation).
- `sdk`: `SdkCatalog` & `SdkManagerRepository` (real device binary probe, storage check, OkHttp streaming download, SHA-256 verification).
- `ai`: `OnlineAiClient` (SSE streaming for Gemini, OpenAI, Anthropic, Custom), `OfflineAiManager` (GGUF download + binary header validation + RAM check), `ProjectContextManager`, `CodingAgentEngine` (diff preview, user approval, checkpointing, rollback).

## 2. SDK & Toolchain Compatibility Matrix
| Toolchain | Execution on Standard Unrooted Android 10+ | Behavior in DevForge AI |
|---|---|---|
| Web (HTML/CSS/JS) | Native via Android `WebView` | Full validation, asset bundling, JS console & live preview |
| Shell (`/system/bin/sh`) | Native OS shell | Real command execution, stdout/stderr, env, cwd, cancellation |
| Gradle / Kotlin / Java / Flutter / RN / CMake | External ELF binaries restricted by Android SELinux W^X (`noexec` in app-private storage) | Probes `PATH` & custom toolchain paths (e.g., Termux); runs real builds when executable, otherwise runs full static pre-flight diagnostics and reports `UNAVAILABLE` truthfully |
| Offline GGUF (`llama.cpp`) | Requires bundled NDK `libllama_jni.so` | Downloads & validates GGUF v1–v5 binary headers and device RAM; reports `libllama_jni.so` absence honestly if not compiled in |

## 3. Building & Generating APKs
1. Ensure Android SDK (compileSdk 36, minSdk 24) and JDK 17+ are configured.
2. Debug APK: `gradle :app:assembleDebug`
3. Release APK: `gradle :app:assembleRelease`
4. Unit & Robolectric Tests: `gradle :app:testDebugUnitTest`
