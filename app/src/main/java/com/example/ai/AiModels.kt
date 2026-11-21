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
  data class ToolCall(val call: com.example.ai.provider.AIToolCallRequest) : AiStreamEvent()
  data class Usage(val usage: AiTokenUsage) : AiStreamEvent()
  data object Completed : AiStreamEvent()
  data class Error(val message: String) : AiStreamEvent()
}
