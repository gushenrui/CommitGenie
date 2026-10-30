# CommitGenie 代码改进与优化清单

## 一、 逻辑缺陷与边界问题

- [x] **修复无历史提交时 `Auto` 语言指令悬空的问题**
    - **位置**：`src/main/kotlin/com/gushenrui/commitgenie/prompt/PromptBuilder.kt`（第 26–37、48–52 行）
    - **现状**：当用户关闭「采样仓库历史提交」、将采样条数设为 `0` 或在全新空仓库首次提交时，
      `styleSection` 为空，但 `language = Auto` 仍会输出
      `"Write the commit message in the same language as the sample commit messages."`。
    - **建议**：在 `languageInstruction` 中结合 `sampleMessages.isNotEmpty()` 判断；若没有历史提交样本且语言为
      `Auto`，不追加该句指令（或回退为默认语言提示）。

- [x] **优化多仓库（Multi-root）项目的历史提交采样来源**
    - **位置**：`src/main/kotlin/com/gushenrui/commitgenie/git/VcsChanges.kt`（第 79–99 行）
    - **现状**：`sampleCommitMessages` 直接按顺序遍历
      `GitRepositoryManager.getInstance(project).repositories`，在第一个仓库采满 `limit` 条后即
      `break`。若项目包含多个 Git 仓库且用户仅勾选了第二个仓库的文件，采样到的却是第一个仓库的提交历史。
    - **建议**：根据当前勾选的 `changes` 定位所属的 `GitRepository` 集合，优先从变更涉及的仓库采样历史提交。

## 二、 性能与交互体验优化

- [x] **拆分网络连接超时（`connectTimeout`）与读取超时（`readTimeout`）**
    - **位置**：`src/main/kotlin/com/gushenrui/commitgenie/model/ModelClient.kt`（第 67–68、319 行）
    - **现状**：`connectTimeout` 和 `readTimeout` 均使用 `DEFAULT_TIMEOUT_MS`（120 秒）。当 Base URL
      主机不可达或端口错误时，用户需等待整整 2 分钟才会收到连接失败提示。
    - **建议**：将 `connectTimeout` 缩短为 `10_000`～`15_000` ms，仅将 `readTimeout` 保持在 `120_000`
      ms 以容纳慢速推理模型。

- [ ] **优化推理模型（Thinking Models）思考阶段的状态反馈**
    - **位置**：`src/main/kotlin/com/gushenrui/commitgenie/model/ModelClient.kt`（第 295–311 行）、
      `src/main/kotlin/com/gushenrui/commitgenie/action/SuggestCommitMessageAction.kt`（第 127–131 行）
    - **现状**：DeepSeek R1、Qwen3 等模型通过 `delta.reasoning_content`（OpenAI 协议）或
      `thinking_delta`（Anthropic 协议）返回思考内容时，`extractOpenAiContent` /
      `extractAnthropicContent` 均返回 `null`，界面遮罩长时间停留在静态的 `Generating commit message`
      ，用户无法判断连接是否正常。
    - **建议**：识别流式思考块事件并回调状态（或将遮罩文案切换为 `"Thinking..."`），对于把
      `<think>...</think>` 混在 `content` 中的模型，也可在流式阶段实时过滤已闭合/进行中的 `<think>`
      内容，避免思考过程在输入框里闪现后再被擦除。

## 三、 文档同步与工程质量

- [ ] **对齐文档描述与代码实现**
    - **位置**：`README.md`（第 19 行）、`src/main/resources/META-INF/plugin.xml`（第 7 行）、
      `build.gradle.kts`（第 50 行）
    - **现状**：
        1. `README.md` 提到“过滤掉 `IDEA additional info:` 等无意义行”，但代码仅清空了
           `beforeVersionId` / `afterVersionId`；
        2. `plugin.xml` 与 `build.gradle.kts` 的插件简介仅写了“使用任意 OpenAI 兼容接口”，漏掉了已支持的
           Anthropic `/messages` 接口。
    - **建议**：同步更新 `plugin.xml`、`build.gradle.kts` 与 `README.md` 的文案。
