package com.gushenrui.commitgenie.settings

import com.gushenrui.commitgenie.model.ModelProtocol
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * 插件全局配置。
 *
 * 除 API Key 之外的字段写入 XML；API Key 单独存入系统钥匙串，避免明文落盘。
 */
@State(name = "CommitGenieSettings", storages = [Storage("commitgenie.xml")])
class CommitGenieSettings : PersistentStateComponent<CommitGenieSettings.State> {

    data class State(
        var baseUrl: String = DEFAULT_BASE_URL,
        var model: String = DEFAULT_MODEL,
        var protocol: ModelProtocol = ModelProtocol.OPENAI,
        var sampleCommitCount: Int = DEFAULT_SAMPLE_COUNT,
        var maxDiffChars: Int = DEFAULT_MAX_DIFF_CHARS,
        var includeHistory: Boolean = true,
        var customPrompt: String = "",
        var language: String = LANGUAGE_AUTO,
    )

    private var myState = State()

    /**
     * 钥匙串读取涉及系统调用，而 [isConfigured] 会在工具栏刷新时被高频调用，
     * 因此只在首次访问或显式写入时读取，其余情况用缓存值。
     */
    private var cachedApiKey: String? = null

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /** API Key 只存在于钥匙串，不参与 XML 序列化。 */
    var apiKey: String
        get() = cachedApiKey ?: (PasswordSafe.instance.getPassword(credentialAttributes()) ?: "").also {
            cachedApiKey = it
        }
        set(value) {
            val trimmed = value.trim()
            cachedApiKey = trimmed
            val credentials = trimmed.takeIf { it.isNotEmpty() }?.let { Credentials("", it) }
            PasswordSafe.instance.set(credentialAttributes(), credentials)
        }

    val isConfigured: Boolean
        get() = myState.baseUrl.isNotBlank() && myState.model.isNotBlank() && apiKey.isNotBlank()

    private fun credentialAttributes(): CredentialAttributes =
        CredentialAttributes(SERVICE_NAME, "apiKey")

    companion object {
        private const val SERVICE_NAME = "CommitGenie"

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_SAMPLE_COUNT = 6
        const val DEFAULT_MAX_DIFF_CHARS = 40_000
        const val LANGUAGE_AUTO = "Auto"

        val instance: CommitGenieSettings
            get() = ApplicationManager.getApplication().service()
    }
}
