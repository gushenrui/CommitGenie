package com.gushenrui.commitgenie.settings

import com.gushenrui.commitgenie.model.ModelProtocol
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import javax.swing.SwingUtilities

class GenerationSnapshotTest {
    @Test
    fun `captured request keeps matching key and config after settings change`() {
        SwingUtilities.invokeAndWait {
            val settings = CommitGenieSettings({ "old-key" }, {}, Executor { it.run() })
            settings.state.baseUrl = "https://old.invalid/v1"
            settings.state.model = "old-model"
            settings.state.customPrompt = "old prompt"
            val request = settings.generationSnapshot()
            // 模拟 diff 构建期间用户在 EDT 应用新配置。
            settings.state.baseUrl = "https://new.invalid/v1"
            settings.state.model = "new-model"
            settings.state.protocol = ModelProtocol.ANTHROPIC
            settings.state.maxDiffChars = 1000
            settings.state.includeHistory = false
            settings.state.sampleCommitCount = 0
            settings.state.customPrompt = "new prompt"
            settings.saveApiKey("new-key")
            assertEquals("old-key", request.apiKey)
            assertEquals("https://old.invalid/v1", request.config.baseUrl)
            assertEquals("old-model", request.config.model)
            assertEquals(ModelProtocol.OPENAI, request.config.protocol)
            assertEquals(40000, request.config.maxDiffChars)
            assertEquals(6, request.config.sampleCommitCount)
            assertTrue(request.config.includeHistory)
            assertEquals("old prompt", request.config.customPrompt)
            val next = settings.generationSnapshot()
            assertEquals("new-key", next.apiKey)
            assertEquals("https://new.invalid/v1", next.config.baseUrl)
            assertEquals(ModelProtocol.ANTHROPIC, next.config.protocol)
        }
    }

    @Test
    fun `snapshot configuration check does not follow subsequent clearing`() {
        SwingUtilities.invokeAndWait {
            val settings = CommitGenieSettings({ "key" }, {}, Executor { it.run() })
            val request = settings.generationSnapshot()
            settings.saveApiKey("")
            assertTrue(request.isConfigured)
            assertFalse(settings.generationSnapshot().isConfigured)
        }
    }
}
