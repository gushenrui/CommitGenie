package com.gushenrui.commitgenie.model

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

class ModelResponseTest {
    private fun client(protocol: ModelProtocol = ModelProtocol.OPENAI) =
        ModelClient("https://unused.invalid", "test", "test", protocol)

    private val delta = """data: {"choices":[{"delta":{"content":"fix: example"}}]}"""
    private val anthropicDelta =
        """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"fix: example"}}"""

    private fun read(text: String, protocol: ModelProtocol = ModelProtocol.OPENAI): String =
        client(protocol).readResponse(StringReader(text).buffered())

    private fun fails(text: String, protocol: ModelProtocol = ModelProtocol.OPENAI): IOException =
        assertThrows(IOException::class.java) { read(text, protocol) }

    @Test
    fun `openai done returns without reading to EOF`() {
        assertEquals(
            "fix: example",
            client().readResponse(noFurtherReads(listOf(delta, "", "data: [DONE]")))
        )
    }

    @Test
    fun `anthropic stop returns without reading to EOF`() {
        val lines = listOf(
            anthropicDelta,
            "",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            "",
            """data: {"type":"message_stop"}""",
            ""
        )
        assertEquals(
            "fix: example",
            client(ModelProtocol.ANTHROPIC).readResponse(noFurtherReads(lines))
        )
    }

    @Test
    fun `partial EOF is rejected for both protocols`() {
        assertTrue(fails("$delta\n\n").message!!.contains("提前结束"))
        assertTrue(
            fails(
                "$anthropicDelta\n\n",
                ModelProtocol.ANTHROPIC
            ).message!!.contains("提前结束")
        )
    }

    @Test
    fun `malformed JSON after content cannot succeed`() {
        assertTrue(fails("$delta\n\ndata: {broken json}\n\ndata: [DONE]\n").message!!.contains("JSON"))
    }

    @Test
    fun `wrong JSON structure cannot succeed`() {
        fails("$delta\n\ndata: {\"choices\":42}\n\ndata: [DONE]\n")
    }

    @Test
    fun `openai output limit and content filter are rejected`() {
        for (reason in listOf("length", "content_filter", "tool_calls")) {
            fails("$delta\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$reason\"}]}\n\ndata: [DONE]\n")
        }
    }

    @Test
    fun `anthropic output limit is rejected`() {
        assertTrue(
            fails(
                "$anthropicDelta\n\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}\n\n",
                ModelProtocol.ANTHROPIC
            ).message!!.contains("长度上限")
        )
    }

    @Test
    fun `stream error is not mistaken for completed output`() {
        fails("$delta\n\ndata: {\"error\":{\"message\":\"overloaded\"}}\n\n")
        fails(
            "$anthropicDelta\n\ndata: {\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}\n\n",
            ModelProtocol.ANTHROPIC
        )
    }

    @Test
    fun `non-streaming JSON single and multiple lines are supported`() {
        assertEquals(
            "fix: example",
            read("""{"choices":[{"message":{"content":"fix: example"},"finish_reason":"stop"}]}""")
        )
        assertEquals(
            "fix: example",
            read("{\n\"choices\": [\n{\"message\":{\"content\":\"fix: example\"}}\n]\n}")
        )
        assertEquals(
            "fix: example",
            read(
                """{"content":[{"type":"text","text":"fix: example"}],"stop_reason":"end_turn"}""",
                ModelProtocol.ANTHROPIC
            )
        )
    }

    @Test
    fun `non-streaming truncated output is rejected`() {
        fails("""{"choices":[{"message":{"content":"fix: partial"},"finish_reason":"length"}]}""")
        fails(
            """{"content":[{"type":"text","text":"fix: partial"}],"stop_reason":"max_tokens"}""",
            ModelProtocol.ANTHROPIC
        )
        fails("""{"choices":[{"message":{"content":"incomplete"}""")
    }

    @Test
    fun `multiline SSE event and heartbeat are handled`() {
        assertEquals(
            "fix: example",
            read(": heartbeat\nevent: completion\ndata: {\"choices\":\ndata: [{\"delta\":{\"content\":\"fix: example\"}}]}\n\ndata: [DONE]\n")
        )
    }

    @Test
    fun `empty or unrelated JSON is not a successful message`() {
        fails("")
        fails("{}")
    }

    @Test
    fun `valid stop reason alone does not hide missing end marker`() {
        fails("$delta\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
    }

    @Test
    fun `final pending delta is published before done`() {
        val updates = mutableListOf<String>()
        assertEquals(
            "fix: example",
            client().readResponse(
                StringReader("$delta\ndata: [DONE]\n").buffered(),
                { updates += it })
        )
        assertEquals(listOf("fix: example"), updates)
    }

    @Test
    fun `anthropic skips leading thinking and collects every text block`() {
        val json =
            """{"content":[{"type":"thinking","thinking":"private"},{"type":"text","text":"fix: "},{"type":"text","text":"example"}],"stop_reason":"end_turn"}"""
        assertEquals("fix: example", read(json, ModelProtocol.ANTHROPIC))
    }

    @Test
    fun `anthropic ignores non-text blocks even when they contain text fields`() {
        val json =
            """{"content":[{"type":"text","text":"fix: "},{"type":"thinking","text":"not output"},{"type":"tool_use","text":"not output"},{"type":"text","text":"example"}],"stop_reason":"end_turn"}"""
        assertEquals("fix: example", read(json, ModelProtocol.ANTHROPIC))
    }

    @Test
    fun `anthropic preserves whitespace between text blocks`() {
        val json =
            """{"content":[{"type":"text","text":"fix: example"},{"type":"text","text":"\n\n"},{"type":"text","text":"Details"}],"stop_reason":"end_turn"}"""
        assertEquals("fix: example\n\nDetails", read(json, ModelProtocol.ANTHROPIC))
    }

    @Test
    fun `anthropic empty and non-text content produce no commit text`() {
        for (content in listOf("[]", """[{"type":"thinking","thinking":"private"}]""")) {
            assertEquals(
                "",
                read("{\"content\":$content,\"stop_reason\":\"end_turn\"}", ModelProtocol.ANTHROPIC)
            )
        }
    }

    @Test
    fun `output limit applies across chunks and allows exact boundary`() {
        fun stream(extra: Boolean): String {
            val chunk = "x".repeat(ModelClient.MAX_OUTPUT_CHARS / 2)
            val data = "data: {\"choices\":[{\"delta\":{\"content\":\"$chunk\"}}]}\n\n"
            return data + data + (if (extra) delta + "\n\n" else "") + "data: [DONE]\n"
        }
        assertEquals(ModelClient.MAX_OUTPUT_CHARS, read(stream(false)).length)
        assertTrue(fails(stream(true)).message!!.contains("字符"))
    }

    @Test
    fun `non-streaming output and oversized SSE events are bounded`() {
        val content = "x".repeat(ModelClient.MAX_OUTPUT_CHARS + 1)
        fails("{\"choices\":[{\"message\":{\"content\":\"$content\"}}]}")
        assertTrue(fails("data: " + "x".repeat(1_048_576)).message!!.contains("事件过大"))
    }

    private fun noFurtherReads(lines: List<String>): BufferedReader =
        object : BufferedReader(StringReader("")) {
            private val iterator = lines.iterator()
            override fun readLine(): String {
                check(iterator.hasNext()) { "Attempted to read after completion; a live connection would block" }
                return iterator.next()
            }
        }
}
