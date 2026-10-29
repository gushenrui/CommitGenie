package com.gushenrui.commitgenie.settings

import com.gushenrui.commitgenie.model.ModelProtocol
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume

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

    private val apiKeyLock = Any()

    /** 钥匙串读取完成后才有值；EDT 只读取这个缓存，不触发系统调用。 */
    @Volatile
    private var cachedApiKey: String? = null

    /** 用户已显式保存过密钥时，后台预读不得再覆盖。 */
    private var apiKeyOverridden = false

    /** 首次钥匙串读取的完成信号，供设置页异步填充和生成任务等待。 */
    private val apiKeyLoaded = CompletableFuture<String>()

    init {
        // 应用启动即在后台预读，避免首次刷新工具栏或打开设置页时卡住 EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = try {
                PasswordSafe.instance.getPassword(credentialAttributes()).orEmpty()
            } catch (e: Exception) {
                // 钥匙串不可用时也要放行等待方，否则设置页和生成任务会一直挂起
                LOG.warn("读取 API Key 失败", e)
                ""
            }
            val value = synchronized(apiKeyLock) {
                if (!apiKeyOverridden) {
                    cachedApiKey = loaded
                }
                cachedApiKey.orEmpty()
            }
            apiKeyLoaded.complete(value)
        }
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /** 尚未加载完成时为 null；调用方不得据此触发钥匙串读取。 */
    val apiKey: String?
        get() = cachedApiKey

    /** 等待首次加载结束后返回最新密钥，可在协程中调用，不阻塞 EDT。 */
    suspend fun awaitApiKey(): String = suspendCancellableCoroutine { continuation ->
        apiKeyLoaded.whenComplete { _, _ ->
            if (continuation.isActive) {
                continuation.resume(cachedApiKey.orEmpty())
            }
        }
    }

    /** 加载完成后在 EDT 回调最新密钥，设置页据此异步填充密码框。 */
    fun loadApiKey(onLoaded: (String) -> Unit) {
        apiKeyLoaded.whenComplete { _, _ ->
            ApplicationManager.getApplication().invokeLater(
                { onLoaded(cachedApiKey.orEmpty()) },
                ModalityState.any(),
            )
        }
    }

    /** 立即更新内存状态，钥匙串写入放到后台，避免保存设置时阻塞 EDT。 */
    fun saveApiKey(value: String) {
        val trimmed = value.trim()
        synchronized(apiKeyLock) {
            apiKeyOverridden = true
            cachedApiKey = trimmed
        }
        apiKeyLoaded.complete(trimmed)
        val credentials = trimmed.takeIf { it.isNotEmpty() }?.let { Credentials("", it) }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                PasswordSafe.instance.set(credentialAttributes(), credentials)
            } catch (e: Exception) {
                // 钥匙串写入失败不影响本次会话使用内存中的密钥
                LOG.warn("保存 API Key 失败", e)
            }
        }
    }

    val isConfigured: Boolean
        get() = myState.baseUrl.isNotBlank() &&
                myState.model.isNotBlank() &&
                !apiKey.isNullOrBlank()

    /** 密钥仍在后台读取；此时不能把「未配置」当作结论。 */
    val isApiKeyPending: Boolean
        get() = apiKey == null
    private fun credentialAttributes(): CredentialAttributes =
        CredentialAttributes(SERVICE_NAME, "apiKey")

    companion object {
        private const val SERVICE_NAME = "CommitGenie"

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_SAMPLE_COUNT = 6
        const val DEFAULT_MAX_DIFF_CHARS = 40_000
        const val LANGUAGE_AUTO = "Auto"

        private val LOG: Logger = Logger.getInstance(CommitGenieSettings::class.java)

        val instance: CommitGenieSettings
            get() = ApplicationManager.getApplication().service()
    }
}
