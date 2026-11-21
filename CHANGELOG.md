# Changelog — DevForge AI

## [2.0.0] — Major IDE Upgrade

### Removed
- **Local / Offline AI Subsystem**: Completely removed offline GGUF download manager, `llama.cpp` JNI placeholders, local model storage files, and offline AI UI tabs without breaking existing user projects, chats, build history, or checkpoints.

### Added
- **Multi-Provider AI System (`com.example.ai.provider`)**:
  - Added `AIProvider`, `AIProviderRegistry`, `AIModelRegistry`, `AITransport`, `ProviderUrlValidator`, `ProviderAuthHelper`, and `ProviderErrorHandler`.
  - Added adapters for Gemini, OpenAI, Anthropic, Custom OpenAI-Compatible, and Custom REST endpoints.
  - Added AndroidKeyStore AES-256-GCM API key encryption (`AndroidKeyStoreSecretManager`), live model discovery, `REMOVED` model detection, manual model CRUD, per-model verification, and conversation/default/agent model switching.
- **MCP Client & Security Layer (`com.example.mcp`)**:
  - Added `McpClientManager`, `McpSecurityPolicy`, `StreamableHttpMcpSession`, `LegacyHttpSseMcpSession`, and honest `StdioMcpSession` (`UNSUPPORTED` on Android).
  - Added JSON-RPC 2.0 handshake, paginated `tools/list`, per-tool allowlists, sensitive-tool approval gates, `[UNTRUSTED_MCP_TOOL_OUTPUT]` prompt-injection boundary, and `mcp_audit_logs` Room table.
- **Universal Code Protection Engine (`com.example.protection`)**:
  - Added `FileTypeDetector`, `FrameworkDetector`, `ProtectionToolRegistry`, `ProtectionProfileManager`, `ProtectionCompatibilityChecker`, `SymbolAndMappingVault`, `ProtectionArtifactVerifier`, `OptInAssetCrypto`, and `ProtectionPipeline`.
  - Integrated protection execution and leak verification directly into `BuildManager`.
- **Build & SDK Improvements**:
  - Added `BuildVariant.RELEASE_AAB`, `BuildErrorDiagnosticClassifier`, `BuildWorker` (WorkManager), RAM/storage pre-checks, SDK reachability probes, and non-destructive Room schema `v1 -> v2` (`MIGRATION_1_2`).
- **UI Screens**:
  - Added `ProjectImportScreen`, `AiProviderManagerScreen`, `ModelSelectorScreen`, `McpManagerScreen`, `ProtectionScreen`, `MappingVaultScreen`, and `BuildHistoryScreen`.
  - Upgraded `DashboardScreen`, `ProjectsScreen`, `BuildManagerScreen`, `SdkManagerScreen`, `AiAssistantScreen`, `SettingsScreen`, and `MainActivity` navigation drawer/rail.
