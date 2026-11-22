# Security Architecture — DevForge AI

## 1. Credential & Mapping Encryption at Rest
- **Master Cipher (`AndroidKeyStoreSecretManager`)**: Generates and stores an AES-256-GCM hardware/OS-backed key (`devforge_ai_master_secret_key`) inside `AndroidKeyStore`.
- **Encrypted Credentials**:
  - AI provider API keys (`ai_providers.encryptedApiKey`) and legacy preferences are encrypted before persistence in Room / DataStore.
  - MCP bearer/custom auth tokens (`mcp_servers.encryptedToken`) are encrypted at rest.
  - R8 mappings, Flutter symbol archives, and JS source maps archived in `SymbolAndMappingVault` are AES-256-GCM encrypted in `filesDir/mapping_vault/*.vault.enc`.
- **Opt-In Asset Encryption (`OptInAssetCrypto`)**: Uses AES-256-GCM (`AES/GCM/NoPadding`, random 96-bit IV, 128-bit auth tag) and generates a paired `DevForgeAssetDecryptor.kt` helper. Never describes HTML/CSS/JSON minification as encryption.

## 2. Transport & Endpoint Security
- **HTTPS-First Validation (`ProviderUrlValidator`, `McpSecurityPolicy`)**: Public `http://` URLs for AI providers and remote MCP servers are blocked. Only `https://` is allowed by default; `http://localhost`, `http://127.0.0.1`, `http://10.0.2.2`, and RFC1918 local-dev LAN endpoints are permitted solely with an explicit cleartext security warning.
- **Secret Redaction (`RedactingLogger`)**: Automatically masks `Bearer`, `sk-*`, `AIza*`, `ghp_*`, `xoxb-*`, API key parameters, and explicit session secrets in logs, error summaries, and MCP audit log entries.

## 3. MCP Tool Execution & Prompt-Injection Defense
- **Allowlists**: Per-server `allowAllTools` (off by default) and `allowedTools` enforce server & tool authorization prior to JSON-RPC execution.
- **Sensitive Action Confirmation**: Tools matching sensitive verbs/patterns (`delete`, `shell`, `exec`, `keystore`, `signing`, `secret`, `deploy`, etc.) are classified as `ToolRiskLevel.SENSITIVE` and blocked until explicitly approved by the user via an interactive confirmation dialog (`PendingMcpToolApproval`).
- **Untrusted Data Boundary**: All MCP tool results are wrapped inside `[UNTRUSTED_MCP_TOOL_OUTPUT]` with explicit non-instruction notices, payload-size capping (`2 MB`), and regex prompt-injection warnings.
- **Audit Logging**: Connect, disconnect, permission denials, security blocks, and tool executions are persisted in `mcp_audit_logs`.

## 4. Filesystem, ZIP, and Build Artifact Hygiene
- **Path Containment (`PathSandbox`)**: Canonicalizes paths and blocks directory traversal outside workspace roots.
- **ZIP Import Guard (`ZipSecurityValidator`)**: Enforces traversal rejection, entry count limits, per-entry limits, and total decompressed byte limits.
- **Artifact Leak Verification (`ProtectionArtifactVerifier`)**: Scans built `.apk`, `.aab`, `.zip`, and `.jar` archives for forbidden files (`mapping.txt`, `usage.txt`, `seeds.txt`, `.keystore`, `.jks`, `.p12`, `.env`, `.pem`, `.symbols`, `.js.map`, `.debug`).
