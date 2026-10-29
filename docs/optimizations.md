# CommitGenie 优化清单（7–16 条）

本文承接前一轮代码审查。第 1–6 条已修复并提交；以下是剩余 7–16 条的详细说明，
按「位置 → 现状 → 问题 → 建议」组织，便于逐条跟进。

> 约定：所有改动都不涉及 `build.gradle.kts`；验证方式为代码审查 + 手工运行 `./install.sh`。

## 总览

| 编号 | 主题                                     | 类型          | 优先级 | 状态   |
|------|------------------------------------------|---------------|--------|--------|
| 7    | 勾选集反射读取缺少缓存                   | 性能          | 中     | 已处理 |
| 8    | 钥匙串读取可能阻塞 EDT                   | 性能 / 体验   | 高     | 已处理 |
| 9    | 先渲染完整 diff 再截断                   | 内存          | 中     | 已处理 |
| 10   | `generate()` 的 `indicator` 参数无人使用 | 清理          | 低     | 已处理 |
| 11   | 手写 JSON 拼接请求体                     | 可维护性      | 中     | 已处理 |
| 12   | `relativePath` 的相对路径基准不一致      | 正确性        | 中     | 已处理 |
| 13   | Action 直接依赖 Project 级协程服务       | 可测试性      | 低     | 已处理 |
| 14   | 未读取错误响应体，400 细分判断失效       | 正确性 / 诊断 | 高     | 已处理 |
| 15   | `temperature` 硬编码为 1                 | 可配置性      | 低     | 已处理 |
| 16   | 模型输出未清理代码围栏与思考标签         | 健壮性        | 中     | 待处理 |

---

## 7. 勾选集反射读取缺少缓存

**位置**：`git/VcsChanges.kt:47-78`

**现状**：每次读取勾选变更都会走一遍 `readHandlerInclusion`：从 `handler.javaClass`
开始逐级向上找名为 `inclusionModel` 的字段，`getDeclaredField` 失败就换父类。

**问题**：`SuggestCommitMessageAction.update()` 运行在 EDT 上，工具栏每次刷新都会调用
`includedChanges()`。字段查找本身开销不大，但有两个连带成本：

1. 每轮刷新都要重新做「类层次遍历 + 异常构造」——`getDeclaredField` 找不到时抛
   `NoSuchFieldException`，异常构造是这里最贵的部分；
2. 为了判断 `isNotEmpty()`，取的是 **完整勾选集合**，集合大时白白复制一遍。

**建议**：

- 把解析结果缓存到 `ChangesViewWorkflowHandler` 的实例上（例如以 handler 为键的
  `WeakHashMap<Any, Field>`），或用 `ClassValue` 按类缓存 `Field`；
- `update()` 只需要「是否有勾选」，加一个 `hasIncludedChanges()` 轻量入口，内部拿到
  集合后立即返回 `isNotEmpty()`，不做逐项分类。

---

## 8. 钥匙串读取可能阻塞 EDT

**位置**：`settings/CommitGenieSettings.kt:44-57`，调用方 `action/SuggestCommitMessageAction.kt:63`

**现状**：`apiKey` 的 getter 在 `cachedApiKey == null` 时会同步调用
`PasswordSafe.instance.getPassword(...)`。`isConfigured` 读 `apiKey`，而 `isConfigured`
又在 `update()`（EDT）里被调用。

**问题**：首次访问会触发系统钥匙串（macOS Keychain）调用。钥匙串未解锁、需要用户确认、
或系统钥匙串服务响应慢时，这次调用会 **直接卡住 EDT**，表现为工具栏/设置页短暂无响应。
`cachedApiKey` 只解决了第二次之后的开销，首屏仍会命中。

**建议**：

- 在应用启动或设置页打开时，用一个后台协程预读一次 API Key 并写入缓存；
- `isConfigured` 改为读取「已知是否存在」的布尔标记（例如 `hasApiKey`），该标记随
  `apiKey` 的 setter 和预读结果更新，不在 EDT 上触发钥匙串读；
- `CommitGenieConfigurable.reset()` 已经在 EDT 上读明文，可一并评估是否放到
  `createComponent` 之后的异步填充。

---

## 9. 先渲染完整 diff 再截断

**位置**：`git/DiffBuilder.kt:29-49`、`65-77`

**现状**：`buildDiff` 先把所有 patch 的全文渲染进一个 `StringBuilder`，最后才调用
`truncateByCodePoint(diff, maxChars)` 截到 `maxChars`。

**问题**：默认上限是 40000 字符，但渲染阶段不受这个上限约束。勾选了多个大文件时，
完整 diff 可能有几 MB 到几十 MB，`StringBuilder` 扩容 + 最后的 `substring` 会产生
数倍于目标尺寸的峰值内存。这一步发生在 IO 线程，但仍会带来 GC 压力和明显的延迟。

**建议**：

- 把 `maxChars` 传入 `render`，在每个 hunk/文件的追加过程中累计长度，超过上限就停止
  追加并跳出；
- 截断仍然按码点处理，保留现有的 `truncateByCodePoint` 作为收尾保险；
- 由于 `patches` 本身已经包含全部内容，可进一步在构建 patch 时按文件大小跳过明显超限
  的文件，并在日志里记录被跳过的文件数。

---

## 10. `generate()` 的 `indicator` 参数无人使用

**位置**：`model/OpenAiClient.kt:34-38`、`71-73`；调用方
`action/SuggestCommitMessageAction.kt:174`

**现状**：`generate(prompt, indicator, onDelta)` 接收一个 `ProgressIndicator?`，
内部在读取循环里检查 `indicator?.isCanceled`。唯一的调用方显式传入 `indicator = null`。

**问题**：该参数已经不再承担任何职责——取消由协程取消 + 主动断开连接实现。保留它会让
后来者误以为存在一条「进度指示器」控制路径，也留下一段永远走不到的死代码。

**建议**：删除 `indicator` 参数以及循环内的 `isCanceled` 检查，同步删除
`ProgressIndicator` 的 import。这是纯清理，行为不变。

---

## 11. 手写 JSON 拼接请求体

**位置**：`model/OpenAiClient.kt:130-162`

**现状**：`requestBody` 用字符串模板拼接，`quote()` 自行处理引号、反斜杠、控制字符、
代理对与孤立代理。

**问题**：手写转义规则需要持续维护才能与 JSON 规范保持一致（例如 `\u0000`–`\u001F`
之外的控制字符、`DEL` 等）。任何遗漏都会让服务端返回 400，而错误信息又往往不含具体
位置，排查成本高。项目已经依赖 Gson（`JsonParser`、`JsonObject` 都在用）。

**建议**：改用 Gson 构建请求体：

```kotlin
val root = JsonObject().apply {
    addProperty("model", model.trim())
    addProperty("stream", true)
    addProperty("temperature", temperature)
    add("messages", JsonArray().apply {
        add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", prompt)
        })
    })
}
Gson().toJson(root)
```

转义、代理对处理全部由 Gson 负责，`quote()` 可以整体删除。

---

## 12. `relativePath` 的相对路径基准不一致

**位置**：`git/DiffBuilder.kt:134-144`

**现状**：

```kotlin
val absolute = if (Paths.get(path).isAbsolute) Paths.get(path)
else (project.basePath?.let { Paths.get(it) } ?: baseDir).resolve(path)
baseDir.relativize(absolute)
```

**问题**：文件名为相对路径时，用 `project.basePath` 作为基准解析，再用 `baseDir` 求相对
路径。`baseDir` 由 `PatchWriter.calculateBaseDirForWritingPatch` 计算，当勾选的变更只
涉及某个模块或子目录时，它与 `project.basePath` **并不相同**，此时算出的相对路径会多出
或缺少前缀，diff 里的 `a/`、`b/` 路径就会与实际不符。

**建议**：两条分支统一以 `baseDir` 为基准解析：

```kotlin
val absolute = if (Paths.get(path).isAbsolute) Paths.get(path) else baseDir.resolve(path)
```

`project` 参数随之只用于取 `baseDir`，可从 `relativePath` 的签名里去掉。

---

## 13. Action 直接依赖 Project 级协程服务

**位置**：`action/SuggestCommitMessageAction.kt:111`，
`action/CommitGenieCoroutineService.kt:18`

**现状**：Action 内部直接 `CommitGenieCoroutineService.getInstance(project).scope.launch { ... }`。

**问题**：`AnAction` 没有构造注入点，生成流程又与 UI 收尾（加载态、输入框还原）紧耦合，
导致这段逻辑无法脱离 IDE 单独测试；同时 Action 同时承担了「按钮状态计算」「参数校验」
「任务编排」「UI 收尾」四种职责，`actionPerformed` 已经接近 70 行。

**建议**：

- 把生成流程抽到一个项目级服务（例如 `CommitMessageGenerator`），对外暴露
  `suspend fun generate(changes, onDelta): Result<String>`，协程作用域在服务内部持有；
- Action 只保留：读取数据 → 判断是否取消 → 调用服务 → 处理返回；
- 服务通过构造函数接收 `CoroutineScope` 与 `CommitGenieSettings`，便于替换为测试替身。

**处理结果**：新增 `action/CommitMessageGenerator.kt`，以 `@Service(Service.Level.PROJECT)`
注册，构造器接收 `CoroutineScope` 与 `CommitGenieSettings`。平台按
`CoroutineScope` 构造器注入的作用域已与项目容器作用域取交集，项目关闭即取消，无需自建
`SupervisorJob`。对外只暴露 `suspend fun generate(project, changes, onDelta): Result`，
用 sealed `Result` 区分「未配置 / diff 为空 / 内容过大 / 超时 / 其他失败」，不再直接弹通知。
Action 侧删除了自带的 `generate()`，只负责读取勾选、占位取消、流式渲染与失败提示；
`CommitGenieCoroutineService` 已删除。

---

## 14. 未读取错误响应体，400 细分判断失效

**位置**：`model/OpenAiClient.kt:62`、`82-106`、`114-120`

**现状**：请求构建时只调用了 `.throwStatusCodeException(true)`，没有开启
`isReadResponseOnError(true)`。抛出 `HttpStatusException` 时，`message` 是平台的本地化
文案（形如 `Request failed with status code 400`）， **不包含服务端返回的 body**。

**问题**：`isPayloadTooLarge(status, detail)` 对 400 的判断依赖 `detail` 中的关键词
（`maximum context`、`too long`、`exceeds` 等）。既然 `detail` 里没有 body，这套判断
在真实场景下几乎不会命中，导致「上下文超限」被统一报成普通的「生成失败：模型接口返回
400」。用户看到的提示无法指导下一步操作。

**建议**：

- 在请求链上补 `.isReadResponseOnError(true)`，让 `HttpStatusException.message` 携带
  响应体；
- 响应体可能很长或含 HTML，写日志与提示前统一截断（例如 500 字符），并优先提取 JSON
  里的 `error.message` 字段；
- 判断顺序调整为先解析 body 再匹配关键词，避免把「鉴权失败」误判为内容超限。

**处理结果**：最初按建议补了 `.isReadResponseOnError(true)`，实现时发现它在本项目里 **不生效**：
平台的 `doProcess` 只在 `connect` 回调 **正常返回后**才检查状态码，而回调内部读取
`request.reader` 时，4xx 会先抛普通 `IOException`、响应体随之丢失，异常退出时那段检查根本
走不到。后来再次确认：回调抛异常后平台 `doProcess` 会先 `request.close()` 并断开连接，
异常分支里再读 `errorStream` 也只能得到空串。

因此最终方案是 **只保留一条路径**：在回调内 `request.write(body)` 之后先读 `responseCode`，
非 2xx 时立即读 `errorStream` 并抛出内部 `HttpErrorException`，把状态码与 body 一起带回。
`.isReadResponseOnError(true)` 已删除，避免留下无效配置。错误流绕过平台的解压逻辑，读取时按
`Content-Encoding` 自行处理 gzip，并按 `Content-Type` 的 charset 解码，长度上限 8 KB。

错误摘要统一由 `errorDetail()` 处理：优先取 `{"error":{"message":...}}` 或
`{"message":...}`（会跳过前置 BOM/日志前缀），解析不出再退回原文；折叠空白后截断到 500
字符（截断点避开代理对）。`isPayloadTooLarge()` 只在 413、400 与流内错误（状态码 -1）上
判断，401/403 等鉴权失败不参与关键词匹配，避免误报为内容超限；`OVERSIZE_HINTS` 也去掉了
`max_tokens` 这类会把参数校验错误误判进来的关键词。3xx 单独提示「请检查 Base URL」。

日志按类型分流：已识别的 HTTP 状态异常只记摘要（`HttpStatusException.toString()` 会把整段
body 带进堆栈），其余意外异常保留堆栈。超时场景不再探测状态码，防止再等一个读超时。

---

## 15. `temperature` 硬编码为 1

**位置**：`model/OpenAiClient.kt:131`

**现状**：请求体里固定写死 `"temperature":1`。

**问题**：提交信息属于低创造性任务，`1` 偏高，模型更容易产出与 diff 无关的措辞；
不同厂商对温度的取值范围与推荐值也不一致，写死无法适配。

**建议**：

- 在 `CommitGenieSettings.State` 增加 `temperature: Double = 1.0` 字段；
- 设置页「生成选项」增加一个数值输入（范围 0.0–2.0，步长 0.1），复用现有的
  `spinner` + `bindValue` 写法；
- 构造请求体时读取该配置；若担心兼容性，可在值等于默认值时省略该字段。

**处理结果**：未按上述建议新增配置项。第 11 条改用 Gson 构建请求体时，`temperature`
字段随之删除；后续确认没有配置需求的场景下继续保留该字段只会增加维护面，因此最终决定
**直接移除、不再发送**，由各厂商网关使用自身的默认温度。当前 `requestBody()` 只写 `model`、`stream`、
`messages`（Anthropic 额外写 `max_tokens`），无遗留引用，README 的配置表也无需变动。

---

## 16. 模型输出未清理代码围栏与思考标签

**位置**：`action/SuggestCommitMessageAction.kt:174`、`196`

**现状**：模型返回结果只经过 `normalize()`（统一换行符）和 `trim()` 就写回输入框。

**问题**：即便提示词要求「只输出提交信息」，实际仍常见三类污染：

1. 整段被 ```` ``` ```` 或 ```` ```text ```` 包裹；
2. 部分推理模型输出 `<think>…</think>`（或 `思考：…`）后再给正文；
3. 前置一句 `Here is the commit message:` 之类的引导语。

这些内容会原样进入提交信息，需要用户手工删除。

**建议**：在 `normalize` 之后增加一个纯函数 `cleanModelOutput(text)`：

- 去掉首尾成对的代码围栏（含带语言标记的形式）；
- 删除 `<think>…</think>` 区块；
- 若首行是常见的引导语且不匹配 `type(scope): subject` 形态，则丢弃该行；
- 该函数无副作用，便于单测覆盖。

**待确认**：是否要连带清理 `<analysis>`、`<reasoning>` 等变体，可在实现时看实际返回
再决定关键字列表。

---

## 后续

- 建议按优先级处理： **14 → 8 → 12 → 16 → 7 → 9 → 11 → 15 → 10 → 13**。
- 每次改动后同步检查 `README.md` 的「已知限制」与「配置」两节是否需要更新
  （尤其第 15 条会新增一个配置项）。
- 本文档在对应条目完成后可勾掉状态列，全部完成后删除。
