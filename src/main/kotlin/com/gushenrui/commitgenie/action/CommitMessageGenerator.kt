package com.gushenrui.commitgenie.action

import com.gushenrui.commitgenie.git.DiffBuilder
import com.gushenrui.commitgenie.git.VcsChanges
import com.gushenrui.commitgenie.model.ModelClient
import com.gushenrui.commitgenie.model.ModelOutputCleaner
import com.gushenrui.commitgenie.prompt.PromptBuilder
import com.gushenrui.commitgenie.settings.CommitGenieSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 提交信息生成流程。
 *
 * 负责「等待密钥 → 构建 diff → 采样历史 → 组装提示词 → 调用模型」，
 * 不直接操作 Swing 组件，便于脱离 Action 单独验证或替换为测试替身。
 *
 * @param scope 平台注入的作用域，已与项目容器作用域取交集，随项目关闭一起取消
 * @param settings 配置来源，测试时可替换为独立实例
 */
@Service(Service.Level.PROJECT)
class CommitMessageGenerator(
    val scope: CoroutineScope,
    private val settings: CommitGenieSettings,
) {

    /** 平台实例化入口：配置固定读取应用级服务。 */
    constructor(scope: CoroutineScope) : this(scope, CommitGenieSettings.instance)

    /** 生成结果，失败时区分可指导用户操作的场景。 */
    sealed interface Result {
        data class Success(val message: String) : Result
        data object NotConfigured : Result
        data object EmptyDiff : Result
        data class PayloadTooLarge(val cause: Throwable) : Result
        data class Timeout(val cause: Throwable) : Result
        data class Failed(val cause: Throwable) : Result
    }

    /**
     * 等待密钥加载完成并确认配置可用。
     *
     * 调用方据此决定是否进入加载态，避免「密钥仍未就绪」时先闪一次遮罩。
     */
    suspend fun awaitConfigured(): Boolean {
        // awaitApiKey 结束前缓存仍为空，此时 isConfigured 会把「未加载」误判为「未配置」
        settings.awaitApiKey()
        return settings.isConfigured
    }

    /**
     * 执行一次生成，取消时抛出 [CancellationException]。
     *
     * @param onDelta 每收到一段增量文本时回调，参数是截至目前的完整文本
     * @return 成功文本或分类后的失败原因，界面收尾与提示由调用方负责
     */
    suspend fun generate(
        project: Project,
        changes: List<Change>,
        onDelta: (String) -> Unit,
    ): Result {
        return try {
            // 先等密钥加载结束再判断配置，避免读到尚未填充的缓存
            val apiKey = settings.awaitApiKey()
            if (apiKey.isBlank() || !settings.isConfigured) return Result.NotConfigured

            val diff = DiffBuilder.buildDiff(project, changes, settings.state.maxDiffChars)
            if (diff.isBlank()) return Result.EmptyDiff

            val samples = if (settings.state.includeHistory) {
                VcsChanges.sampleCommitMessages(project, settings.state.sampleCommitCount)
            } else {
                emptyList()
            }

            val prompt = PromptBuilder.build(samples, diff, settings.state)
            val client = ModelClient(
                baseUrl = settings.state.baseUrl,
                apiKey = apiKey,
                model = settings.state.model,
                protocol = settings.state.protocol,
            )

            // 增量回调提前统一换行并去掉开头的空行，避免输入框先闪出空白；
            // 最终结果再清理围栏、思考区块与前置引导语，避免污染提交信息
            val text = ModelOutputCleaner.clean(
                normalize(
                    client.generate(prompt) { raw -> onDelta(normalize(raw).trimStart()) }
                )
            )
            if (text.isEmpty()) Result.Failed(IOException("模型返回空内容")) else Result.Success(
                text
            )
        } catch (e: CancellationException) {
            // 取消必须继续向上传播，否则调用方无法区分取消与失败
            throw e
        } catch (e: ModelClient.PayloadTooLargeException) {
            Result.PayloadTooLarge(e)
        } catch (e: SocketTimeoutException) {
            Result.Timeout(e)
        } catch (e: Exception) {
            Result.Failed(e)
        }
    }

    private fun normalize(text: String): String = text.replace("\r\n", "\n")

    companion object {
        fun getInstance(project: Project): CommitMessageGenerator = project.service()
    }
}
