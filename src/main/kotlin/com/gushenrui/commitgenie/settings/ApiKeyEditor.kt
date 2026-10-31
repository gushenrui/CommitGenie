package com.gushenrui.commitgenie.settings

import javax.swing.JPasswordField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** 密码框的加载与编辑状态；所有调用均在 EDT 上进行。 */
internal class ApiKeyEditor(private val field: JPasswordField) {
    private var baseline: String? = null
    private var edited = false
    private var updating = false

    init {
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
            private fun changed() {
                if (!updating) edited = true
            }
        })
    }

    val isModified: Boolean
        get() = if (baseline == null) edited else value() != baseline

    fun reset(key: String?) {
        baseline = key
        updating = true
        try {
            field.text = key.orEmpty()
        } finally {
            updating = false
        }
        edited = false
    }

    fun fillIfUntouched(key: String) {
        if (baseline == null && !edited) reset(key)
    }

    fun apply(save: (String) -> Unit) {
        // 未加载且未编辑的空框不是删除指令；已加载但未改变也无需重复写入。
        if (!isModified) return
        val key = value().trim()
        save(key)
        reset(key)
    }

    private fun value(): String = field.password.concatToString()
}
