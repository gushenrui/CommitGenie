# CommitGenie

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

为 Android Studio / IntelliJ IDEA 提供 AI 生成 Git 提交信息的能力，复刻 Android Studio 内置 Gemini
插件「Suggest commit message」的交互与提示词，但 **完全独立**：不依赖 Gemini 插件，可自由接入任意 OpenAI
兼容接口。

在提交面板的消息输入框工具栏点击按钮，或按 `⌃\`（macOS），即可根据 **当前勾选的变更**生成提交信息，生成过程中可随时取消。

## 特性

- **只处理勾选的变更**：读取提交面板的勾选状态，未被勾选的文件不会进入 diff。
- **参考仓库历史风格**：默认采样最近 6 条提交信息，让模型模仿项目既有的书写习惯（也支持关掉）。
- **流式生成**：边生成边显示，长提交信息无需干等。
- **随时取消**：生成中再次点击按钮或再次按 `⌃\` 即取消。
- **富文本提示（rich tooltip）**：按钮在禁用态也会说明原因——未勾选文件、未配置模型、处于 Amend 模式等。
- **Diff 精简与截断**：过滤掉 `IDEA additional info:` 等无意义行，按码点安全截断（不会把 emoji 劈成非法
  UTF-8）。
- **可编辑提示词**：内置提示词与原版语义一致，也可用 `$COMMIT_DIFF` / `$SAMPLE_COMMIT_MESSAGES`
  宏完全自定义。
- **API Key 安全存储**：写入系统钥匙串，不落盘到配置文件。

## 环境要求

| 项目     | 要求                                                                          |
|----------|-------------------------------------------------------------------------------|
| IDE      | Android Studio 2026.2.1（build 262），`since-build=262` / `until-build=262.*` |
| VCS      | Git（依赖 `Git4Idea` 插件，随 IDE 内置）                                      |
| 系统     | macOS（安装脚本按 `/Applications/Android Studio.app` 定位 IDE）               |
| 模型接口 | 任意 OpenAI 兼容的 `/chat/completions` 接口                                   |

## 安装

```bash
./install.sh              # 打包并安装到用户插件目录（推荐，无需 sudo）
./install.sh --system     # 安装到 Android Studio 应用包内（需要写权限）
./install.sh --build-only # 只打包，产物在 build/distributions/*.zip
```

安装完成后重启 Android Studio。验证方式：

- `Settings → Tools → CommitGenie` 出现配置页；
- 提交面板的消息输入框工具栏出现生成按钮；
- 快捷键 `⌃\` 生效。

构建使用 Android Studio 自带的 JBR（JDK 25），脚本会自动设置 `JAVA_HOME`。构建阶段有 600 秒看门狗，可用
`WATCHDOG_SECONDS=1800 ./install.sh` 放宽。

## 配置

`Settings → Tools → CommitGenie`：

| 配置项                       | 默认值                      | 说明                                                       |
|------------------------------|-----------------------------|------------------------------------------------------------|
| Base URL                     | `https://api.openai.com/v1` | 需包含版本路径；插件会拼接 `/chat/completions`             |
| Model                        | `gpt-4o-mini`               | 例如 `deepseek-chat`、`qwen-plus` 等                       |
| API Key                      | 空                          | 保存在系统钥匙串                                           |
| 输出语言                     | `Auto`                      | 填 `Auto` 跟随历史提交语言，也可填 `Chinese`、`English` 等 |
| 历史提交采样条数             | `6`                         | 填 `0` 表示不采样                                          |
| Diff 最大字符数              | `40000`                     | 超出部分截断，避免请求体过大                               |
| 采样仓库历史提交作为风格参考 | 开启                        | 关闭后不读取历史提交                                       |
| 自定义提示词                 | 空                          | 留空使用内置提示词                                         |

自定义提示词可用两个宏：

- `$COMMIT_DIFF`：本次勾选变更的 unified diff
- `$SAMPLE_COMMIT_MESSAGES`：采样到的历史提交信息

## 使用

1. 在提交面板中勾选要本次提交的文件。
2. 点击消息输入框工具栏的 CommitGenie 按钮，或按 `⌃\`。
3. 生成期间输入框显示 `Generating commit message`；再次点击按钮即可取消。
4. 生成结果写入提交信息输入框，可继续编辑后提交。

> Amend 模式下按钮会禁用并提示原因——此时提交信息应描述原始提交，不宜由当前变更推断。

## 实现要点

生成流程：

```
勾选变更 → 构建 diff → 采样历史提交 → 组装提示词 → SSE 流式请求 → 写回输入框
```

- **勾选状态读取**：平台未公开非模态提交面板的勾选集。代码优先走
  `SingleChangeListCommitWorkflowUi.getInclusionModel()`（模态提交对话框），失败时反射读取
  `ChangesViewCommitWorkflowHandler` 内部的 `PartialCommitInclusionModel`
  。两条路径都失败时返回空列表，不做兜底，以便直接暴露问题。
- **Diff 构建**：平台公开的 `UnifiedDiffWriter` 标注为 `@ApiStatus.Internal`，因此自行渲染 unified
  diff 头与 hunk，二进制文件单独渲染，单个文件构建失败只记录日志、不影响其余文件。
- **请求层**：`HttpRequests.post(endpoint, "application/json")` 的第二个参数是 **Content-Type**，请求体在
  `connect` 回调中通过 `request.write(...)` 写入；解析标准 `data:` 事件流，同时兼容部分网关直接返回整段
  JSON 的行为。
- **错误细分**：413 直接判定为请求体过大；400 结合响应文案关键词判断。前者提示缩小勾选范围，超时则提示稍后重试。

### 目录结构

```
src/main/kotlin/com/gushenrui/commitgenie/
├── action/     # Action、协程服务、加载态、通知、图标
├── git/        # 勾选变更读取、diff 构建、历史提交采样
├── model/      # OpenAI 兼容客户端（SSE 流式）
├── prompt/     # 提示词组装
└── settings/   # 配置持久化与设置页
```

## 已知限制

- 仅支持 build 262（Android Studio 2026.2.1）；其他版本需要在 `build.gradle.kts` 中调整 `sinceBuild` /
  `untilBuild` 并重新验证 API。
- 非模态提交面板的勾选状态依赖反射，IDE 内部结构调整后可能失效。
- 未实现 Gemini 原版的 `.aiexclude` 排除规则。
- Amend 模式不支持生成。
- 不读取 IDE 的代理设置，网络代理需在系统或网关层配置。

## 致谢

提示词语义与交互设计参考 Android Studio 内置 Gemini 插件的「Suggest commit
message」功能。本项目为独立实现，与该功能无官方关联。

## 许可

[MIT](LICENSE)。
