package com.example.ai

enum class PatchOperation {
  CREATE,
  MODIFY,
  DELETE,
}

data class FilePatch(
  val relativePath: String,
  val operation: PatchOperation,
  val rationale: String,
  val newContent: String,
)

data class AgentPlan(
  val summary: String,
  val steps: List<String>,
  val patches: List<FilePatch>,
)

data class AiTokenUsage(
  val promptTokens: Int = 0,
  val completionTokens: Int = 0,
)

sealed class AiStreamEvent {
  data class Token(val text: String) : AiStreamEvent()
  data class Usage(val usage: AiTokenUsage) : AiStreamEvent()
  data object Completed : AiStreamEvent()
  data class Error(val message: String) : AiStreamEvent()
}

data class GgufHeaderInfo(
  val version: Int,
  val tensorCount: Long,
  val metadataKvCount: Long,
)

data class OfflineModelSpec(
  val id: String,
  val displayName: String,
  val fileName: String,
  val downloadUrl: String,
  val sizeBytes: Long,
  val minRamBytes: Long,
  val contextLength: Int,
  val notes: String,
)

data class InstalledOfflineModel(
  val filePath: String,
  val fileName: String,
  val sizeBytes: Long,
  val ggufHeader: GgufHeaderInfo?,
  val isValidGguf: Boolean,
)

object OfflineModelCatalog {
  val models: List<OfflineModelSpec> =
    listOf(
      OfflineModelSpec(
        id = "smollm2-1.7b-q4_k_m",
        displayName = "SmolLM2 1.7B Instruct (Q4_K_M)",
        fileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
        downloadUrl =
          "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
        sizeBytes = 1_055_000_000L,
        minRamBytes = 3_000_000_000L,
        contextLength = 8192,
        notes = "Lightweight general & scripting model (~1.05 GB).",
      ),
      OfflineModelSpec(
        id = "qwen2.5-coder-1.5b-q4_k_m",
        displayName = "Qwen2.5-Coder 1.5B Instruct (Q4_K_M)",
        fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        downloadUrl =
          "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        sizeBytes = 1_120_000_000L,
        minRamBytes = 3_000_000_000L,
        contextLength = 32768,
        notes = "Code-focused compact model for mobile RAM constraints.",
      ),
      OfflineModelSpec(
        id = "qwen2.5-coder-3b-q4_k_m",
        displayName = "Qwen2.5-Coder 3B Instruct (Q4_K_M)",
        fileName = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        downloadUrl =
          "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        sizeBytes = 2_070_000_000L,
        minRamBytes = 5_000_000_000L,
        contextLength = 32768,
        notes = "Higher accuracy code generation; requires >= 5 GB device RAM.",
      ),
    )
}
