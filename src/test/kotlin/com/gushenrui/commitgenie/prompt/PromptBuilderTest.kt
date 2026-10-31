package com.gushenrui.commitgenie.prompt

import com.gushenrui.commitgenie.settings.CommitGenieSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    @Test
    fun `individual history samples are bounded without splitting emoji`() {
        val markerLength = "… [sample truncated]".length
        val sample =
            "x".repeat(PromptBuilder.MAX_SAMPLE_CHARS - markerLength - 1) + "😀" + "x".repeat(100)
        val result = build(PromptBuilder.MACRO_SAMPLES, listOf(sample), "")
        assertTrue(result.contains("[sample truncated]"))
        assertTrue(result.length <= PromptBuilder.MAX_SAMPLE_CHARS + 100)
        assertTrue(result.none { Character.isSurrogate(it) })
    }

    @Test
    fun `aggregate history budget includes formatting`() {
        val result = build(PromptBuilder.MACRO_SAMPLES, List(50) { "x".repeat(10000) }, "")
        assertTrue(result.length <= PromptBuilder.MAX_SAMPLES_CHARS)
        assertTrue(result.endsWith("==== End of examples ===="))
    }

    @Test
    fun `oversized template is rejected before expansion`() {
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            build("x".repeat(PromptBuilder.MAX_TEMPLATE_CHARS + 1), emptyList(), "")
        }
    }

    @Test
    fun `repeated macros cannot exceed the final budget`() {
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            build(PromptBuilder.MACRO_DIFF.repeat(10), emptyList(), "x".repeat(60000))
        }
    }

    @Test
    fun `exact prompt budget is accepted but appended notices are checked`() {
        val result =
            build(PromptBuilder.MACRO_DIFF, emptyList(), "x".repeat(PromptBuilder.MAX_PROMPT_CHARS))
        assertEquals(PromptBuilder.MAX_PROMPT_CHARS, result.length)
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            PromptBuilder.checkBudget(
                result + "x"
            )
        }
    }

    @Test
    fun `default prompt overhead counts toward final budget`() {
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            build("", emptyList(), "x".repeat(PromptBuilder.MAX_PROMPT_CHARS))
        }
    }

    private val diffMacro = PromptBuilder.MACRO_DIFF
    private val samplesMacro = PromptBuilder.MACRO_SAMPLES

    private fun build(template: String, samples: List<String>, diff: String) = PromptBuilder.build(
        samples, diff, CommitGenieSettings.State(customPrompt = template),
    )

    @Test
    fun `macros inside replacement values are not expanded`() {
        val sample = "docs: explain $diffMacro and $samplesMacro"
        val diff = "+literal $samplesMacro $diffMacro"
        val renderedSamples =
            "==== Example commit message 1 ====\n$sample\n==== End of examples ===="
        assertEquals(
            "$renderedSamples\n$diff",
            build("$samplesMacro\n$diffMacro", listOf(sample), diff)
        )
        assertEquals(
            "$diff\n$renderedSamples",
            build("$diffMacro\n$samplesMacro", listOf(sample), diff)
        )
    }

    @Test
    fun `all repeated original macros are replaced once`() {
        assertEquals("D|D|", build("$diffMacro|$diffMacro|$samplesMacro", emptyList(), "D"))
    }

    @Test
    fun `replacement dollars and backslashes are literal`() {
        val diff = "+value=\$1\\path\\name"
        assertEquals(diff, build(diffMacro, emptyList(), diff))
    }

    @Test
    fun `unknown macros and custom text remain unchanged`() {
        assertEquals(
            "Use \$UNKNOWN literally",
            build("Use \$UNKNOWN literally", emptyList(), "diff")
        )
    }

    @Test
    fun `default prompt preserves macros in supplied content`() {
        val sample = "docs: explain $diffMacro"
        val diff = "+$samplesMacro"
        val result = build("", listOf(sample), diff)
        assertTrue(result.contains(sample))
        assertTrue(result.contains(diff))
    }
}
