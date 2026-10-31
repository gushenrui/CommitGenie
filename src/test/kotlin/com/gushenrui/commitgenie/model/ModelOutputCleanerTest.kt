package com.gushenrui.commitgenie.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelOutputCleanerTest {
    @Test
    fun `paired tags within commit subject are preserved`() {
        for (tag in listOf("think", "thinking", "analysis", "reasoning", "reflection")) {
            val text = "fix: preserve <$tag>report</$tag> markup"
            assertEquals(text, ModelOutputCleaner.clean(text))
        }
    }

    @Test
    fun `tags in body and code samples are preserved`() {
        val text =
            "fix: preserve markup\n\n<analysis>report</analysis>\n\n```xml\n<think>example</think>\n```"
        assertEquals(text, ModelOutputCleaner.clean(text))
    }

    @Test
    fun `only leading thinking blocks are stripped`() {
        val body = "fix: preserve <analysis>report</analysis>"
        assertEquals(
            body,
            ModelOutputCleaner.clean("  <THINK mode=\"slow\">first\nstep</THINK>\n<reflection>second</reflection>\n$body")
        )
    }

    @Test
    fun `unclosed leading block is discarded but body literal remains`() {
        assertEquals("", ModelOutputCleaner.clean("<think>incomplete reasoning"))
        val text = "fix: parse <think> without a closing tag"
        assertEquals(text, ModelOutputCleaner.clean(text))
    }

    @Test
    fun `wrappers are removed without deleting body tags`() {
        val body = "fix: preserve <analysis>report</analysis>"
        assertEquals(
            body,
            ModelOutputCleaner.clean("Here is the commit message:\n```text\n<think>reason</think>\n$body\n```")
        )
    }

    @Test
    fun `streaming and final cleanup preserve the same body tags across chunks`() {
        val body = "fix: preserve <analysis>report</analysis>"
        val chunks =
            listOf("<thi", "nk>reason</thi", "nk>\nfix: preserve <ana", "lysis>report</analysis>")
        val stream = chunks.joinToString("") { chunk ->
            "data: {\"choices\":[{\"delta\":{\"content\":${Gson().toJson(chunk)}}}]}\n\n"
        } + "data: [DONE]\n"
        val result = ModelClient("https://unused.invalid", "test", "test", ModelProtocol.OPENAI)
            .readResponse(stream.reader().buffered())
        assertEquals(body, result.trim())
        assertEquals(body, ModelOutputCleaner.clean(result))
    }
}
