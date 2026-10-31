package com.gushenrui.commitgenie.settings

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import javax.swing.JPasswordField
import javax.swing.SwingUtilities

class ApiKeyLifecycleTest {
    private class Tasks : Executor {
        val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() {
            tasks.removeAt(0).run()
        }
    }

    @Test
    fun `applying untouched pending field preserves stored key`() = onEdt {
        val tasks = Tasks()
        val writes = mutableListOf<String>()
        val settings = CommitGenieSettings({ "stored" }, { writes += it }, tasks)
        val field = JPasswordField()
        val editor = ApiKeyEditor(field)
        editor.reset(settings.apiKey)
        settings.state.model = "another-model"
        editor.apply(settings::saveApiKey)
        assertTrue(settings.isApiKeyPending)
        tasks.runNext()
        editor.fillIfUntouched(settings.apiKey!!)
        assertEquals("stored", field.password.concatToString())
        assertTrue(writes.isEmpty())
        assertFalse(editor.isModified)
    }

    @Test
    fun `user replacement wins over delayed initial read`() = onEdt {
        val tasks = Tasks()
        val writes = mutableListOf<String>()
        val settings = CommitGenieSettings({ "old" }, { writes += it }, tasks)
        val field = JPasswordField()
        val editor = ApiKeyEditor(field)
        editor.reset(null)
        field.text = " new "
        editor.fillIfUntouched("old")
        assertEquals(" new ", field.password.concatToString())
        editor.apply(settings::saveApiKey)
        tasks.runNext()
        tasks.runNext()
        assertEquals("new", settings.apiKey)
        assertEquals(listOf("new"), writes)
        assertFalse(editor.isModified)
    }

    @Test
    fun `explicit clear after load is persisted only once`() = onEdt {
        val field = JPasswordField()
        val editor = ApiKeyEditor(field)
        val writes = mutableListOf<String>()
        editor.reset("old")
        field.text = ""
        editor.apply { writes += it }
        editor.apply { writes += it }
        assertEquals(listOf(""), writes)
        assertFalse(editor.isModified)
    }

    @Test
    fun `typing then clearing before load is an explicit deletion`() = onEdt {
        val field = JPasswordField()
        val editor = ApiKeyEditor(field)
        val writes = mutableListOf<String>()
        editor.reset(null)
        field.text = "temporary"
        field.text = ""
        editor.fillIfUntouched("old")
        assertTrue(editor.isModified)
        editor.apply { writes += it }
        assertEquals(listOf(""), writes)
        editor.fillIfUntouched("old")
        assertEquals("", field.password.concatToString())
    }

    @Test
    fun `reset discards unsaved edits and untouched loaded field is not written`() = onEdt {
        val field = JPasswordField()
        val editor = ApiKeyEditor(field)
        editor.reset("stored")
        field.text = "unsaved"
        editor.reset("stored")
        editor.apply { fail("Unchanged key must not be saved") }
        assertEquals("stored", field.password.concatToString())
        assertFalse(editor.isModified)
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
