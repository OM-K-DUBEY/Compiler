# DevForge AI

**DevForge AI** is a native Android IDE built with **Kotlin**, **Jetpack Compose (Material 3)**, **Room**, **DataStore**, **WorkManager**, **OkHttp**, and **AndroidKeyStore**.

## Key Capabilities

1. **Project Workspace & Safe Archive Handling**
   - Multi-template workspace creation (`ANDROID_KOTLIN`, `ANDROID_JAVA`, `KOTLIN_CLI`, `JAVA_CLI`, `FLUTTER`, `WEB_APP`).
   - Sandboxed SAF ZIP import/export (`PathSandbox`, `ZipSecurityValidator` with path-traversal and zip-bomb limits) and SHA-256 verified local backups.
2. **Code Editor & Static Diagnostics**
   - Multi-tab editor with expandable/collapsible file tree, crash-recovery buffers in Room, undo/redo, code folding, formatting, autocomplete, project-wide search/replace, and static syntax diagnostics (`DiagnosticsEngine`) with jump-to-line support.
3. **Interactive Shell Terminal**
   - Real `/system/bin/sh` process streaming with working directory tracking, deduplicated custom `PATH`, environment variables, command history, IME Send action, clipboard output copy, cancellation, and confirmation prompts for scripts.
4. **Multi-Provider Online AI (No Local/Offline AI)**
   - First-class provider adapters for **Google Gemini**, **OpenAI**, **Anthropic Claude**, **Custom OpenAI-Compatible**, and **Custom REST Adapter**.
   - Editable Base URLs (HTTPS enforced by default; local-dev HTTP allowed with explicit security warning), AndroidKeyStore AES-256-GCM encrypted API keys, real authenticated `Test Connection`, live model discovery, manual model entry, per-conversation/default/agent model switching, opt-in-only fallback, SSE streaming, cancellation, and fenced code block rendering with copy buttons.
5. **MCP Client & Security Boundary**
   - Model Context Protocol client (`Streamable HTTP`, `Legacy HTTP+SSE`, honest `UNSUPPORTED` reporting for `STDIO_LOCAL` on Android) with JSON-RPC 2.0 initialization, paginated `tools/list`, per-tool allowlists, `[UNTRUSTED_MCP_TOOL_OUTPUT]` prompt-injection wrapping, sensitive-action user approval dialogs, secret redaction, and persisted audit logs.
6. **AI Coding Agent**
   - Budgeted multi-file context selection, MCP tool-calling loop, scrollable unified & per-file diff previews (`Accept All` / `Accept Selected` / `Reject`), checkpoint snapshots, post-apply static diagnostics, and one-tap rollback.
7. **Universal Code Protection & Mapping Vault**
   - Profiles (`None`, `Basic`, `Balanced`, `Strong`, `Custom`), automatic language/framework detection (`Java`, `Kotlin`, `Android`, `Flutter/Dart`, `Web`), Android R8/ProGuard keep-rule generation, Flutter obfuscation flag preparation, built-in string-safe JS/HTML/CSS/JSON minification, opt-in AES-256-GCM asset encryption, encrypted `SymbolAndMappingVault`, and pre-release archive leak verification (`ProtectionArtifactVerifier`).
8. **Build Manager & SDK Manager**
   - Debug APK, Release APK, and Release AAB build orchestration, WorkManager background build queue (`BuildWorker`), structured `BuildErrorDiagnosticClassifier` + raw logs, hardware RAM/storage pre-checks, SDK checksum verification, and honest device availability probing.

## Architecture & Documentation
- [Architecture Overview](ARCHITECTURE.md)
- [Feature Verification Status](FEATURE_STATUS.md)
- [Build & APK Guide](BUILD_GUIDE.md)
- [Security Model](SECURITY.md)
- [Changelog](CHANGELOG.md)
