# CommitGenie 代码审查报告

审查日期：2026-10-31

审查基线：`68b897f`（审查开始时工作区干净）

范围：全部 13 个 Kotlin 源文件、插件描述文件、Gradle 构建配置、安装脚本及 README。此次只新增报告，没有修改业务代码。

## 结论

### 第一阶段修复进度（2026-10-31）

- **B01 已修复**：密码框独立记录加载基线与用户编辑状态；未加载且未编辑时不保存密钥，显式清空仍可删除。旧值回填不会覆盖用户编辑，保存后同步比较基线。
- **B02 已修复**：后台写入通过 `CompletableFuture` 任务链串行执行，后一次保存或清空必须等待前一次完成；一次存储异常不阻断后续写入。
- 新增 `ApiKeyLifecycleTest`（5 项）和 `ApiKeyPersistenceTest`（3 项）。使用模拟凭据存储和可控任务/延迟，8
  项全部通过，无跳过；没有访问真实钥匙串。
- 验证：使用 IDE 自带 JBR 执行 `./gradlew test --offline --console=plain`，编译及测试通过；
  `git diff --check` 通过。未进行真实 IDE 设置窗口与系统钥匙串交互测试。
- 以下问题描述及审查记录保留原始基线，B03–B08 尚未在本阶段处理。O04 中的密码框比较基线已随 B01
  更新，持久化失败的 UI 提示仍待处理。

发现 **8 项需要修复的问题**、 **5 项优化建议**及 **3 项需要 IDE 场景验证的风险**
。优先修复密钥保存竞态、流式响应完整性判断和 diff 截断问题。

严重级别：P1 为可能造成配置丢失、应优先修复；P2 为特定场景下功能或结果错误；P3
为低影响问题或优化项。这里的“确认”包括源码可直接推导的错误路径，并不意味着全部经过真实 IDE 操作复现。

| 编号 | 级别 | 问题                                             | 验证方式             |
|------|------|--------------------------------------------------|----------------------|
| B01  | P1   | 密钥尚未加载时应用其他设置会删除原密钥           | 源码路径确认         |
| B02  | P2   | 密钥后台写入无顺序保证，旧值可能覆盖新值         | 并发执行顺序推导     |
| B03  | P2   | diff 截断留下不完整 hunk，且不能可靠停止后续构建 | 源码 + 编译产物探针  |
| B04  | P2   | 输出清理误删正文中的标签及其内容                 | 编译产物探针复现     |
| B05  | P2   | 流式结果缺少完成校验，半截内容可能被当成成功     | 源码 + 编译产物探针  |
| B06  | P2   | Anthropic 非流式兼容分支只读取第一个内容块       | 编译产物探针复现     |
| B07  | P2   | 首次安装时插件目录不存在就直接失败               | Shell 条件与源码确认 |
| B08  | P2   | 自定义提示词宏替换会再次替换历史样本里的宏       | 字符串替换顺序确认   |

## 已确认问题

### B01 · 密钥预读完成前保存其他设置，会清空已有密钥

-
位置：[CommitGenieConfigurable.kt:145](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/settings/CommitGenieConfigurable.kt:145)、[CommitGenieSettings.kt:101](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/settings/CommitGenieSettings.kt:101)。
- 触发：系统钥匙串读取较慢，设置页密码框仍为空；用户只修改 Model 等其他配置并点击 Apply。
- 原因：`apply()` 无条件调用 `saveApiKey(currentApiKey())`。空密码框会被保存为删除凭据的 `null`，同时设置
  `apiKeyOverridden = true`，使稍后读到的原密钥也无法恢复缓存。
- 影响：用户没有主动清空密钥，却丢失原配置；后续生成提示未配置，重启后也可能无法恢复。
- 建议：区分“尚未加载”“用户修改”“用户明确清空”。仅在密钥已加载或用户确实编辑了密码框时保存；未加载且未编辑时保留凭据。
- 验收：延迟 PasswordSafe 读取，修改其他配置并 Apply，确认没有发生密钥写入或删除；另测用户明确清空的行为。

### B02 · 多次保存密钥可能以相反顺序落盘

-
位置：[CommitGenieSettings.kt:101](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/settings/CommitGenieSettings.kt:101)。
- 触发：连续保存 A、B 两个密钥，或保存后立即清空；第一个后台任务比第二个任务晚执行。
- 原因：锁只保护内存缓存；每次 `PasswordSafe.set` 都独立提交到共享线程池，没有保证提交顺序就是写入顺序。
- 影响：当前会话使用 B，持久化状态却可能最终是 A；重启后回退到旧密钥。删除操作也可能被旧任务覆盖。
- 建议：用串行写入队列，或在串行写入边界内结合版本号丢弃过期请求。单纯在实际写入前检查版本号，仍需防止检查与写入之间的竞态。
- 验收：使用可控凭据存储替身，让 A 延迟、B 先到，最后持久化结果必须是 B；清空也应遵守同样顺序。

### B03 · diff 的整行限长并不保证 hunk 完整，也不能可靠触发停止

-
位置：[DiffBuilder.kt:103](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/git/DiffBuilder.kt:103)、[DiffBuilder.kt:191](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/git/DiffBuilder.kt:191)。
- 触发：文件头和 hunk 头能放下，但某个正文行超过剩余预算；压缩后的单行 JSON、锁文件尤其容易触发。
- 原因一：先写入完整 hunk 行数声明，再逐行写正文；中途失败直接返回，头部声明的行数与实际正文不一致。注释所说的“整块
  hunk 放不下时不写残缺头部”没有实现。
- 原因二：`appendIfFits` 失败不记录耗尽状态；`isFull` 仅检查 `buffer.length >= limit`
  。若剩余空间大于零，后续文件仍然构建，且可能继续追加到已截断的前一个 hunk 后。
- 实测：构造容量 10 的 `LimitedText`，追加 `123456` 后再追加 `abcdef`，结果是 `APPEND=false`、
  `FULL=false`。
- 影响：模型获得不完整且未标注的变更，可能漏掉核心修改；大量文件下仍有额外 patch 构建开销。仅文件头也会通过后续
  `diff.isBlank()` 检查。
- 建议：预计算并原子追加完整 hunk，或按实际保留行数重写 hunk 头；明确维护截断状态。将截断和跳过文件的信息返回上层，向用户提示。
- 验收：覆盖单行超过上限、恰好达到上限、首个 hunk 放不下、多个文件及 emoji 场景；断言结构完整、总长度受限、停止策略一致。

### B04 · 最终清理会删除合法正文中的成对标签

-
位置：[ModelOutputCleaner.kt:40](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/model/ModelOutputCleaner.kt:40)。
- 原因：`THINK_BLOCK.replace(text, "")` 全文替换 `<analysis>…</analysis>`、`<think>…</think>`
  等内容，没有限制为开头的推理区块。
- 实测输入：`fix: preserve <analysis>report</analysis> markup`。
- 实测输出：`fix: preserve  markup`。
- 影响：当提交描述的是标签、模板或解析器修改时，实际语义被删除；流式过滤器已开始显示正文后会保留这些标签，最终清理却又删除，前后行为不一致。
- 建议：只删除输出前导位置的推理块；识别到正文后保留字面标签。统一流式过滤与最终清理的规则。
- 验收：前导推理块被移除；正文和代码片段里的成对标签保持原样。

### B05 · 没有区分正常完成与流中断，且结束标记不会终止读取

-
位置：[ModelClient.kt:93](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/model/ModelClient.kt:93)、[ModelClient.kt:325](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/model/ModelClient.kt:325)、[ModelClient.kt:370](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/model/ModelClient.kt:370)。
- 触发一：已收到部分正文，网关提前关闭响应体并表现为普通 EOF；或者后续 JSON 损坏。
- 原因：EOF 直接 `finish()`；JSON 解析失败直接返回空更新；没有记录协议完成状态。生成器只要清理后不为空就返回
  `Success`。
- 实测：依次输入包含 `fix: partial` 的增量、损坏的 `data: {bad json}`，再调用 `finish()`，仍返回
  `fix: partial`，没有错误。
- 触发二：收到 `[DONE]` 后连接仍保持打开。当前实现仅忽略该行，外层继续 `readLine()`，直到关闭或读超时。Anthropic
  结束事件也没有传递终止状态。
- 影响：半截提交信息可能被当成完成；已完整生成的结果也可能因继续等待而超时、被还原。
- 建议：让解析器返回内容、完成、错误等明确状态；正常结束时停止读取；未完成 EOF、损坏数据和输出上限截断应有明确策略。非流式
  JSON 单独判断完成，避免误伤兼容分支。
- 验收：完整流、完成后保持连接、半途 EOF、损坏数据、输出长度截断、HTTP 200 内错误和普通 JSON 响应分别验证。

### B06 · Anthropic 非流式结果只读取第一个内容块

-
位置：[ModelClient.kt:423](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/model/ModelClient.kt:423)。
- 触发：兼容网关忽略 `stream: true`，返回含多个 `content` 块的 JSON。
- 原因：代码只对 `content.firstOrNull()` 读取 `text`，不遍历剩余块。
- 实测：输入
  `{"content":[{"type":"thinking","thinking":"internal"},{"type":"text","text":"fix: example"}]}`
  ，收集器最终输出为空。
- 影响：首块不是正文时误报“模型返回空内容”；多个正文块时仅保留第一块。
- 建议：按顺序拼接所有 `type == "text"` 的内容块，忽略其他类型；兼容分支和流式分支分别测试。
- 验收：单正文块、多正文块、思考块在前、空内容数组均返回正确结果。

### B07 · 首次安装时未创建用户插件目录

- 位置：[install.sh:101](/Users/lgw/CommitGenie/install.sh:101)。
- 触发：全新 IDE 配置尚未创建 `$HOME/Library/Application Support/Google/<版本>/plugins`。
- 原因：直接执行 `[[ ! -w "$INSTALL_ROOT" ]]`，不存在的目录同样不满足可写条件；脚本在创建目录之前就退出，且后续也没有创建逻辑。
- 影响：推荐的默认安装命令无法在部分首次安装环境工作；提示改用默认命令不能解决问题。
- 建议：用户模式先 `mkdir -p`，再判断权限；系统模式则保留应用目录检查。尽量在耗时构建之前验证安装目标。
- 验收：在临时目录模拟“目录不存在”“已有且可写”“权限不足”三个状态；无需安装到真实 IDE。

### B08 · 自定义模板对已插入的历史提交再次展开宏

-
位置：[PromptBuilder.kt:19](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/prompt/PromptBuilder.kt:19)。
- 触发：自定义模板包含 `$SAMPLE_COMMIT_MESSAGES`，历史提交恰好提到字面量 `$COMMIT_DIFF`
  。本项目讨论提示词宏的提交就是可能出现该文本的场景。
- 原因：先替换历史样本，再对整个结果执行 `.replace(MACRO_DIFF, diff)`；后一步也会替换样本内部的文本。
- 示例：历史样本 `docs: explain $COMMIT_DIFF` 被变成 `docs: explain <本次完整 diff>`。若模板本身也含
  diff 宏，请求中将重复嵌入完整 diff。
- 影响：样本被篡改，请求膨胀，可能额外触发上下文上限。
- 建议：对原始模板做一次宏扫描，由回调返回替换值，替换值不再参与展开；简单调换替换顺序会产生对称问题。
- 验收：历史样本和 diff 内各自包含两个宏字面量时，只有原模板中的宏被替换。

## 优化建议

### O01 · 合并流式 UI 更新，限制输出累积量（P2）

-
位置：[SuggestCommitMessageAction.kt:128](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/action/SuggestCommitMessageAction.kt:128)、[ModelClient.kt:322](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/model/ModelClient.kt:322)。
- 每次增量都复制截至当前的完整字符串，并送入 `Channel.UNLIMITED`；EDT 再逐条替换整个编辑器文本。输出较长或
  UI 忙碌时会积压大量重复前缀，增加分配和重绘成本。
- 因为通道内容是完整快照，可使用 `Channel.CONFLATED` 或按时间间隔刷新，只保留最新快照；结束时仍写入最终清理结果。另为累计输出设置合理上限。
- 验证：模拟数千个小增量，比较峰值队列、内存与 EDT 响应，并确认最终文本完整。

### O02 · 每次生成使用不可变配置快照（P2）

-
位置：[CommitMessageGenerator.kt:73](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/action/CommitMessageGenerator.kt:73)。
- 生成任务先取得 API Key，之后经过 diff 与历史读取，再多次读取可变 `settings.state`
  。期间修改配置可能使旧密钥与新地址、新协议组合使用。
- 建议在启动阶段一次性取得配置及密钥快照，后续全流程使用同一份快照；定义设置更新只影响下一次生成。
- 验证：人为延迟 diff 构建，中途修改接口配置，确认请求仍使用一致的一组参数。

### O03 · 对最终 prompt 建立整体预算（P2）

-
位置：[PromptBuilder.kt:15](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/prompt/PromptBuilder.kt:15)、[VcsChanges.kt:98](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/git/VcsChanges.kt:98)。
- 目前仅限制 diff 字符数；历史读取的是完整提交正文，自定义模板也没有限长。即使缩小 diff，巨大历史样本仍可能让请求超限。
- 建议分别限制单条样本与样本总量，再校验最终 prompt；若要按 token 限制，应允许模型差异，避免把字符数宣称为准确
  token 数。

### O04 · 保存设置后更新密码框比较基线并反馈持久化失败（P3）

-
位置：[CommitGenieConfigurable.kt:138](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/settings/CommitGenieConfigurable.kt:138)、[CommitGenieSettings.kt:112](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/settings/CommitGenieSettings.kt:112)。
- `apply()` 保存密钥后没有更新 `loadedApiKey`，如果设置窗口继续打开，`isModified()`
  仍可能认为密钥未保存；后台保存失败也只有日志，没有给用户持久化失败提示。
- 建议同步规范化后的 UI 值和比较基线，并把持久化结果回传 UI，明确区分当前会话可用与已经保存。

### O05 · 建立可重复执行的最小回归测试与可配置构建路径（P2/P3）

-
位置：[build.gradle.kts:1](/Users/lgw/CommitGenie/build.gradle.kts:1)、[install.sh:13](/Users/lgw/CommitGenie/install.sh:13)。
- 仓库没有 `src/test`；`test` 任务成功不等于有测试覆盖。优先为清理器、提示词宏、流解析状态、diff
  预算及设置竞态增加边界测试。
- 把 IDE 安装路径作为 Gradle 属性或环境参数，保留当前路径为默认值，便于其他机器和 CI 使用。固定 build
  262 是当前已声明的兼容范围，本报告不将其本身视为 bug。

## 需进一步验证的风险

以下没有完成真实 IDE 复现，不纳入已确认的 8 项问题。

1.
**取消操作可能恢复了错误的提交输入框。** [SuggestCommitMessageAction.kt:81](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/action/SuggestCommitMessageAction.kt:81)
将任务存于 project，但取消时使用本次事件取得的 `messageUi`，任务对象没有保存原输入框。如果同一项目可从另一提交
UI 触发取消，可能把旧快照写到另一输入框，同时原输入框未恢复。需验证目标 IDE 是否存在该交互路径；可通过任务持有原
UI 来约束归属。

2.
**二进制文件限流范围不完整。** [DiffBuilder.kt:171](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/git/DiffBuilder.kt:171)
仅包装 afterRevision，删除文件及 beforeRevision
不受该包装限制；超大文件计算的还可能是前缀哈希而非完整内容哈希。需实测大文件修改与删除的峰值内存，以及“只修改上限之后的字节”时的输出。若只为模型提供变更摘要，优先使用路径、状态、大小和显式省略标记。

3. **CPU/同步 VCS
   阶段取消响应不足。** [DiffBuilder.kt:37](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/git/DiffBuilder.kt:37)
   循环没有显式
   `ensureActive()`；[VcsChanges.kt:92](/Users/lgw/CommitGenie/src/main/kotlin/com/gushenrui/commitgenie/git/VcsChanges.kt:92)
   使用 `runCatching`，会捕获其中抛出的取消异常。底层平台也可能自行检查取消，故不能只凭本文件断言必然卡住。需在大量文件与慢
   Git 历史读取时实测，必要时补充取消检查并重抛取消异常。

部分提交的 hunk 选择也值得增加集成回归；本次检查到目标 IDE 的
`DefaultPatchBuilder → IdeaTextPatchBuilder → PartialChangesUtil.processPartialChanges`
调用链，因此没有仅凭插件传入 `List<Change>` 就判定其会发送全部未勾选代码。

## 验证记录与边界

- 使用 Android Studio 自带 JBR 执行 `./gradlew compileKotlin test --offline --console=plain`：
  **BUILD SUCCESSFUL**，9 个任务中 3 个执行、6 个为最新状态。
- 默认 shell 的 Java 是 17，项目目标是 JVM 25，因此验证命令显式设置了 JBR 的 `JAVA_HOME`。首次执行受到
  Gradle 缓存目录写权限限制，经授权后完成离线验证。
- `bash -n install.sh`：通过语法检查；没有执行安装、删除插件或修改 IDE 设置。
- 在临时目录使用 Java 反射探针对本次编译产物调用实际清理器、限长缓冲区和流式收集器。输出如下：

```text
CLEAN=fix: preserve  markup
APPEND=false
FULL=false
ANTHROPIC=
TRUNCATED=fix: partial
```

- 探针属于临时审查验证，不是仓库内的正式测试套件。未调用真实模型 API、读取真实密钥或进行真实钥匙串写入。
- 未启动 IDE 做交互回归，也未执行插件兼容性验证器；工具栏可见性、模态窗口、取消时机和部分提交行为仍需集成验证。
- 本报告是此次代码范围内的审查结果，不代表不存在其他问题。

## 建议处理顺序

1. 修复 B01、B02，并补上密钥生命周期和并发回归测试。
2. 修复 B03、B05，确保发送的 diff 和接收的提交信息不会静默残缺。
3. 修复 B04、B06、B08，覆盖内容清理与响应/模板边界。
4. 修复 B07，再处理 UI 更新、配置快照、请求预算和构建可移植性。
5. 在目标 IDE 上验证取消、多提交 UI、大二进制文件和部分提交场景。
