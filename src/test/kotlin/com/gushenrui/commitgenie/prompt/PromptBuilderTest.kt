package com.gushenrui.commitgenie.prompt

import com.gushenrui.commitgenie.settings.CommitGenieSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
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
