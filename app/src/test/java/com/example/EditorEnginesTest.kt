package com.example

import com.example.editor.CodeFoldingEngine
import com.example.editor.CodeFormatter
import com.example.editor.CodeLanguage
import com.example.editor.CompletionEngine
import com.example.editor.DiagnosticSeverity
import com.example.editor.DiagnosticsEngine
import com.example.editor.DiffEngine
import com.example.editor.DiffLineType
import com.example.editor.SearchEngine
import com.example.editor.SyntaxHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditorEnginesTest {

  @Test
  fun detectsLanguagesByExtension() {
    assertEquals(CodeLanguage.KOTLIN, CodeLanguage.fromFileName("MainActivity.kt"))
    assertEquals(CodeLanguage.JAVA, CodeLanguage.fromFileName("MainActivity.java"))
    assertEquals(CodeLanguage.GRADLE, CodeLanguage.fromFileName("app/build.gradle.kts"))
    assertEquals(CodeLanguage.DART, CodeLanguage.fromFileName("lib/main.dart"))
    assertEquals(CodeLanguage.TSX, CodeLanguage.fromFileName("App.tsx"))
    assertEquals(CodeLanguage.PLAIN, CodeLanguage.fromFileName("engine.cpp"))
    assertEquals(CodeLanguage.PLAIN, CodeLanguage.fromFileName("script.py"))
  }

  @Test
  fun syntaxHighlighterAndBracketMatchingWork() {
    val code = "fun main() {\n  println(\"ok\")\n}"
    val match = SyntaxHighlighter.findMatchingBracket(code, 12)
    assertNotNull(match)
    assertEquals(11 to code.lastIndex, match)

    val annotated = SyntaxHighlighter.highlight(code, CodeLanguage.KOTLIN, isDark = true, matchedBracketIndices = match)
    assertEquals(code, annotated.text)
    assertTrue(annotated.spanStyles.isNotEmpty())
  }

  @Test
  fun diagnosticsDetectsErrorsInKotlinJsonAndXml() {
    val badKt = "fun broken() {\n  val s = \"unclosed\n"
    val ktDiags = DiagnosticsEngine.analyze("Main.kt", badKt)
    assertTrue(ktDiags.any { it.severity == DiagnosticSeverity.ERROR && it.message.contains("Unterminated string") })
    assertTrue(ktDiags.any { it.severity == DiagnosticSeverity.ERROR && it.message.contains("Unclosed '{'") })

    val badJson = """{"name": }"""
    val jsonDiags = DiagnosticsEngine.analyze("package.json", badJson)
    assertTrue(jsonDiags.any { it.severity == DiagnosticSeverity.ERROR })

    val badXml = "<manifest><application></manifest>"
    val xmlDiags = DiagnosticsEngine.analyze("AndroidManifest.xml", badXml)
    assertTrue(xmlDiags.any { it.severity == DiagnosticSeverity.ERROR })

    val goodXml = "<manifest><application /></manifest>"
    assertTrue(DiagnosticsEngine.analyze("AndroidManifest.xml", goodXml).none { it.severity == DiagnosticSeverity.ERROR })
  }

  @Test
  fun completionAndFormattingWork() {
    val code = "fun computeTotalScore() {}\nfun main() {\n  compute\n}"
    val cursor = code.indexOf("compute\n") + "compute".length
    val suggestions = CompletionEngine.suggest(code, cursor, CodeLanguage.KOTLIN)
    assertTrue(suggestions.any { it.label == "computeTotalScore" })

    val messyJson = """{"a":1,"b":[2,3]}"""
    val formatted = CodeFormatter.format(messyJson, CodeLanguage.JSON, 2)
    assertTrue(formatted.contains("\n"))
  }

  @Test
  fun searchReplaceDiffAndFoldingWork() {
    val text = "fun demo() {\n  val alpha = 1\n  val beta = alpha + 2\n}"
    val (replaced, count) = SearchEngine.replaceAllInText(text, "alpha", "gamma")
    assertEquals(2, count)
    assertTrue(replaced.contains("val gamma = 1"))

    val diff = DiffEngine.computeDiff(text, replaced)
    assertTrue(diff.any { it.type == DiffLineType.REMOVED })
    assertTrue(diff.any { it.type == DiffLineType.ADDED })

    val regions = CodeFoldingEngine.detectFoldRegions(text)
    assertEquals(1, regions.size)
    val folded = CodeFoldingEngine.collapseContent(text, setOf(regions.first().startLine), regions)
    assertTrue(folded.contains("lines folded"))
  }
}
