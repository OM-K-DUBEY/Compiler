package com.example.terminal

import com.example.core.common.AppDispatchers
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class TerminalLineKind {
  COMMAND,
  STDOUT,
  STDERR,
  SYSTEM,
}

data class TerminalLine(
  val text: String,
  val kind: TerminalLineKind,
  val timestampMillis: Long = System.currentTimeMillis(),
)

class TerminalSessionManager(
  private val initialDir: File,
  private val dispatchers: AppDispatchers,
) {
  private val _lines =
    MutableStateFlow(
      listOf(
        TerminalLine(
          text = "DevForge Shell ready (/system/bin/sh). Working dir: ${initialDir.absolutePath}",
          kind = TerminalLineKind.SYSTEM,
        )
      )
    )
  val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

  private val _workingDir = MutableStateFlow(initialDir)
  val workingDir: StateFlow<File> = _workingDir.asStateFlow()

  private val _isRunning = MutableStateFlow(false)
  val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

  private val basePath: String = System.getenv("PATH") ?: "/system/bin:/system/xbin"

  private val _envVars = MutableStateFlow<Map<String, String>>(defaultEnv())
  val envVars: StateFlow<Map<String, String>> = _envVars.asStateFlow()

  private val _history = MutableStateFlow<List<String>>(emptyList())
  val history: StateFlow<List<String>> = _history.asStateFlow()

  private val activeProcess = AtomicReference<Process?>(null)

  fun setWorkingDirectory(dir: File) {
    if (dir.exists() && dir.isDirectory) {
      _workingDir.value = dir.canonicalFile
      appendLine("cwd -> ${dir.canonicalPath}", TerminalLineKind.SYSTEM)
    }
  }

  fun setCustomPathPrefix(customPath: String) {
    val cleaned = customPath.trim()
    val updatedPath = if (cleaned.isEmpty()) basePath else "$cleaned:$basePath"
    _envVars.value = _envVars.value + ("PATH" to updatedPath)
  }

  fun clearOutput() {
    _lines.value = emptyList()
  }

  fun cancelRunningCommand() {
    val proc = activeProcess.getAndSet(null)
    if (proc != null) {
      proc.destroyForcibly()
      appendLine("^C Process terminated", TerminalLineKind.STDERR)
      _isRunning.value = false
    }
  }

  fun requiresScriptConfirmation(command: String): Boolean {
    val trimmed = command.trim()
    return trimmed.startsWith("./") ||
      trimmed.contains(".sh ") ||
      trimmed.endsWith(".sh") ||
      trimmed.startsWith("sh ") ||
      trimmed.startsWith("bash ")
  }

  suspend fun execute(rawCommand: String) =
    withContext(dispatchers.io) {
      val cmd = rawCommand.trim()
      if (cmd.isEmpty()) return@withContext

      _history.value = (_history.value + cmd).takeLast(100)
      appendLine("${_workingDir.value.name} $ $cmd", TerminalLineKind.COMMAND)

      // Built-in directory change
      if (cmd == "cd" || cmd.startsWith("cd ")) {
        handleCd(cmd.removePrefix("cd").trim())
        return@withContext
      }
      if (cmd == "clear") {
        clearOutput()
        return@withContext
      }
      if (cmd.startsWith("export ") && cmd.contains('=')) {
        val assignment = cmd.removePrefix("export ").trim()
        val key = assignment.substringBefore('=').trim()
        val value = assignment.substringAfter('=').trim().trim('"', '\'')
        if (key.isNotEmpty()) {
          _envVars.value = _envVars.value + (key to value)
          appendLine("Exported $key", TerminalLineKind.SYSTEM)
        }
        return@withContext
      }

      _isRunning.value = true
      try {
        val shell = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
        val pb = ProcessBuilder(shell, "-c", cmd).directory(_workingDir.value)
        pb.environment().putAll(_envVars.value)
        pb.environment()["PWD"] = _workingDir.value.absolutePath

        val process = pb.start()
        activeProcess.set(process)

        val stdoutThread =
          Thread {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
              while (true) {
                val line = reader.readLine() ?: break
                appendLine(line, TerminalLineKind.STDOUT)
              }
            }
          }
        val stderrThread =
          Thread {
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
              while (true) {
                val line = reader.readLine() ?: break
                appendLine(line, TerminalLineKind.STDERR)
              }
            }
          }
        stdoutThread.start()
        stderrThread.start()
        val code = process.waitFor()
        stdoutThread.join(2000)
        stderrThread.join(2000)

        if (code != 0) {
          appendLine("[exit code $code]", TerminalLineKind.STDERR)
        }
      } catch (e: Exception) {
        appendLine("Execution error: ${e.message}", TerminalLineKind.STDERR)
      } finally {
        activeProcess.set(null)
        _isRunning.value = false
      }
    }

  private fun handleCd(arg: String) {
    val target =
      when {
        arg.isEmpty() || arg == "~" -> initialDir
        arg.startsWith("/") -> File(arg)
        else -> File(_workingDir.value, arg)
      }.canonicalFile

    if (target.exists() && target.isDirectory) {
      _workingDir.value = target
      appendLine(target.absolutePath, TerminalLineKind.SYSTEM)
    } else {
      appendLine("cd: no such directory: $arg", TerminalLineKind.STDERR)
    }
  }

  private fun appendLine(text: String, kind: TerminalLineKind) {
    _lines.value = (_lines.value + TerminalLine(text, kind)).takeLast(800)
  }

  private fun defaultEnv(): Map<String, String> =
    mapOf(
      "PATH" to (System.getenv("PATH") ?: "/system/bin:/system/xbin"),
      "HOME" to initialDir.absolutePath,
      "TERM" to "xterm-256color",
      "LANG" to "C.UTF-8",
    )
}
