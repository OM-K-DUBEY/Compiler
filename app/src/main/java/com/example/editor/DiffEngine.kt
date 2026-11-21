package com.example.editor

enum class DiffLineType {
  CONTEXT,
  ADDED,
  REMOVED,
}

data class DiffLine(
  val type: DiffLineType,
  val oldLineNumber: Int?,
  val newLineNumber: Int?,
  val text: String,
)

object DiffEngine {

  fun computeDiff(original: String, updated: String): List<DiffLine> {
    val a = if (original.isEmpty()) emptyList() else original.lines()
    val b = if (updated.isEmpty()) emptyList() else updated.lines()
    val m = a.size
    val n = b.size

    // Guard for huge files
    if (m * n > 250_000) {
      val result = mutableListOf<DiffLine>()
      a.forEachIndexed { idx, s -> result += DiffLine(DiffLineType.REMOVED, idx + 1, null, s) }
      b.forEachIndexed { idx, s -> result += DiffLine(DiffLineType.ADDED, null, idx + 1, s) }
      return result
    }

    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
      for (j in n - 1 downTo 0) {
        dp[i][j] =
          if (a[i] == b[j]) dp[i + 1][j + 1] + 1
          else maxOf(dp[i + 1][j], dp[i][j + 1])
      }
    }

    val out = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < m && j < n) {
      when {
        a[i] == b[j] -> {
          out += DiffLine(DiffLineType.CONTEXT, i + 1, j + 1, a[i])
          i++
          j++
        }
        dp[i + 1][j] >= dp[i][j + 1] -> {
          out += DiffLine(DiffLineType.REMOVED, i + 1, null, a[i])
          i++
        }
        else -> {
          out += DiffLine(DiffLineType.ADDED, null, j + 1, b[j])
          j++
        }
      }
    }
    while (i < m) {
      out += DiffLine(DiffLineType.REMOVED, i + 1, null, a[i])
      i++
    }
    while (j < n) {
      out += DiffLine(DiffLineType.ADDED, null, j + 1, b[j])
      j++
    }
    return out
  }

  fun formatUnifiedDiff(path: String, original: String, updated: String): String {
    val diff = computeDiff(original, updated)
    val sb = StringBuilder()
    sb.appendLine("--- a/$path")
    sb.appendLine("+++ b/$path")
    for (line in diff) {
      when (line.type) {
        DiffLineType.CONTEXT -> sb.appendLine(" ${line.text}")
        DiffLineType.REMOVED -> sb.appendLine("-${line.text}")
        DiffLineType.ADDED -> sb.appendLine("+${line.text}")
      }
    }
    return sb.toString().trimEnd()
  }
}
