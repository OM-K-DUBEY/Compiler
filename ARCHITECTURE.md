# DevForge AI — Architecture, Package Structure & Compatibility Matrix

## 1. Architecture Overview
DevForge AI follows a clean **MVVM + Repository** architecture using **Kotlin**, **Jetpack Compose (Material 3)**, **Navigation Compose**, **Room (v2 with non-destructive `MIGRATION_1_2`)**, **DataStore**, **WorkManager**, **OkHttp**, and **AndroidKeyStore**. Dependencies are wired via constructor injection in `com.example.di.AppContainer`.

### Package Structure
- `core/common`: `AppDispatchers`, hardware/RAM/storage/network probes (`DeviceInfoProvider`), secret-redacting logger (`RedactingLogger`).
- `core/security`: `AndroidKeyStoreSecretManager` (`SecretCipher` AES-256-GCM), `PathSandbox`, `ZipSecurityValidator`, `KeystoreSigningManager`.
- `core/database`: Room `DevForgeDatabase` (v2) with entities/DAOs for `projects`, `build_history`, `ai_messages`, `checkpoints`, `editor_tabs`, `sdk_installs`, `ai_providers`, `ai_models`, `mcp_servers`, `mcp_audit_logs`, `protection_profiles`, `mapping_vault`, and `build_protection_reports`.
- `core/preferences`: `PreferencesRepository` backed by Jetpack DataStore (default provider/chat/agent models, opt-in auto-fallback, online code-upload consent, editor prefs, custom toolchain path).
- `core/storage`: `ProjectWorkspaceManager`, `ProjectTemplates`, `ZipArchiveManager`.
- `ai/provider`: `AiProviderContracts`, `ProviderUrlValidator`, `ProviderErrorHandler`, `AITransport`, `ProviderAuthHelper`, `GeminiProviderAdapter`, `OpenAiProviderAdapter`, `AnthropicProviderAdapter`, `CustomRestProviderAdapter`, `AIProviderRegistry`, `AIModelRegistry`.
- `ai`: `OnlineAiClient`, `ProjectContextManager`, `CodingAgentEngine`, `AiAndAgentController`, `AiModels`.
- `mcp`: `McpModels`, `McpSecurityPolicy`, `McpTransports` (`StreamableHttpMcpSession`, `LegacyHttpSseMcpSession`, `StdioMcpSession`), `McpClientManager`.
- `protection`: `ProtectionModels`, `FileTypeDetector`, `FrameworkDetector`, `ProtectionToolAdapters`, `SymbolAndMappingVault`, `ProtectionPipeline`.
- `compiler`: `BuildModels`, `ProjectDetector`, `BuildErrorDiagnosticClassifier`, `BuildWorker`, `BuildManager`.
- `sdk`: `SdkCatalog`, `SdkModels`, `SdkManagerRepository`.
- `editor` & `terminal`: `DiagnosticsEngine`, `CompletionEngine`, `CodeFormatter`, `SearchEngine`, `DiffEngine`, `CodeFoldingEngine`, `TerminalSessionManager`.
- `ui`: Theme, Navigation (`DevForgeRoutes` — 15 routes), `MainIdeViewModel`, and 15 Material 3 Compose screens.

## 2. SDK, Build & Protection Compatibility Matrix
| Capability | Execution on Standard Unrooted Android 10+ | Honest Behavior in DevForge AI |
|---|---|---|
| Online AI Providers & Streaming | Full native HTTPS/SSE via OkHttp | Real authenticated requests, model discovery, verification & error categorization |
| Remote MCP Servers (`HTTP` / `SSE`) | Full native JSON-RPC 2.0 over HTTP/SSE | Full handshake, pagination, allowlists, risk gate & audit logs |
| Local `STDIO` MCP Servers | Spawning arbitrary host binaries unsupported | Explicitly marked `UNSUPPORTED` with clear explanation |
| Web (HTML/CSS/JS) Build & Protection | Full native bundling, `WebView` runtime & JS/Web minifier | Produces verified clean ZIP bundles + live `WebView` preview |
| Android R8 / ProGuard | Rules generation native; execution requires Gradle/JDK | Generates merged `.pro` rules & vaults mappings; reports `UNAVAILABLE` if `gradle` absent |
| Flutter / CMake / Python Protection | External native binaries (`flutter`, `cmake`, `llvm-strip`, `pyarmor`, `nuitka`) | Probes `PATH` & custom toolchain; marks `UNAVAILABLE` unless binary exists |
| Shell (`/system/bin/sh`) | Native OS shell | Real command execution, env, cwd, cancellation |
