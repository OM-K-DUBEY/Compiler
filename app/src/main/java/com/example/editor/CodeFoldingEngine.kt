package com.example.editor

data class FoldRegion(
  val startLine: Int,
  val endLine: Int,
  val headerPreview: String,
)

object CodeFoldingEngine {

  fun detectFoldRegions(content: String): List<FoldRegion> {
    val lines = content.lines()
    val openLines = ArrayDeque<Int>()
    val regions = mutableListOf<FoldRegion>()

    lines.forEachIndexed { idx, raw ->
      val lineNum = idx + 1
      val openCount = raw.count { it == '{' }
      val closeCount = raw.count { it == '}' }
      repeat(openCount) { openLines.addLast(lineNum) }
      repeat(closeCount) {
        val start = openLines.removeLastOrNull()
        if (start != null && lineNum - start >= 2) {
          val preview = lines[start - 1].trim().take(60)
          regions += FoldRegion(start, lineNum, preview)
        }
      }
    }
    return regions.sortedBy { it.startLine }
  }

  fun collapseContent(content: String, collapsedStartLines: Set<Int>, regions: List<FoldRegion>): String {
    if (collapsedStartLines.isEmpty()) return content
    val hiddenLines = mutableSetOf<Int>()
    val foldPlaceholders = mutableMapOf<Int, Int>()
    for (region in regions) {
      if (region.startLine in collapsedStartLines) {
        foldPlaceholders[region.startLine] = region.endLine - region.startLine
        for (l in (region.startLine + 1)..region.endLine) {
          hiddenLines += l
        }
      }
    }
    val lines = content.lines()
    val out = mutableListOf<String>()
    lines.forEachIndexed { idx, line ->
      val lineNum = idx + 1
      if (lineNum !in hiddenLines) {
        val foldedCount = foldPlaceholders[lineNum]
        if (foldedCount != null) {
          out += "$line /* ... $foldedCount lines folded ... */"
        } else {
          out += line
        }
      }
    }
    return out.joinToString("\n")
  }
}
