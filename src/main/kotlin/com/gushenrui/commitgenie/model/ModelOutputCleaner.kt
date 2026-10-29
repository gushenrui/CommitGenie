package com.gushenrui.commitgenie.model

import com.gushenrui.commitgenie.model.ModelOutputCleaner.LEAD_IN_PREFIXES


/**
 * 清理模型输出中不属于提交信息的内容。
 *
 * 提示词已要求只输出提交信息，实际仍常见首尾代码围栏、思考区块与前置引导语；
 * 这里统一剥离，避免这些内容原样写进提交消息输入框。所有步骤都是纯字符串处理。
 */
object ModelOutputCleaner {

    /**
     * 逐层删除思考区块、推理标题、代码围栏与首行引导语。
     *
     * 这些内容可能交替出现（例如先有「Sure! Here is the commit message:」这样的双重引导语，
     * 删掉一层后才露出代码围栏），因此循环剥离直到结果不再变化。
     *
     * @return 清理后的提交信息；没有可用内容时返回空串
     */
    fun clean(text: String): String {
        var result = text.trim()
        while (true) {
            // 每步之间重新 trim：思考区块被删掉后残留的空行会让成对围栏不再首尾对齐
            val stripped = stripThinkBlocks(result).trim()
            val next = stripLeadIn(stripFence(stripReasoningHeader(stripped))).trim()
            if (next == result) return result
            result = next
        }
    }

    /**
     * 删除 `<think>…</think>` 等思考区块。
     *
     * 成对区块只删区块本身，保留其余正文。输出被截断时可能缺少闭合标签：
     * 只有未闭合标签位于文本开头（即整段都是思考过程）才截断其后内容，
     * 出现在正文中段的孤立标签保持原样，避免误删正文。
     */
    private fun stripThinkBlocks(text: String): String {
        val closed = THINK_BLOCK.replace(text, "")
        // 只剩开头这个未闭合标签时，其后内容属于被截断的思考过程；其余位置的孤立标签保持原样
        val open = THINK_OPEN.find(closed)?.takeIf { it.range.first == 0 } ?: return closed
        return closed.substring(0, open.range.first)
    }

    /**
     * 去掉独占首行的推理标题及其后的推理段落。
     *
     * 推理内容与正文之间通常空一行，因此优先用提交信息标题的形态定位正文起点；
     * 找不到可识别的正文标题时，只丢弃标题下紧跟的第一段，宁可多留也不误删。
     * 标题行自带内容（`思考：用户要求……`）且后方没有正文标题时，整段保持原样。
     */
    private fun stripReasoningHeader(text: String): String {
        val lines = text.lines()
        val first = lines.firstOrNull()?.trim().orEmpty()
        val header = REASONING_HEADER.find(first)?.takeIf { it.range.first == 0 } ?: return text
        val rest = lines.drop(1)
        val subjectIndex = rest.indexOfFirst {
            it.isNotBlank() && CONVENTIONAL_SUBJECT.containsMatchIn(it.trim())
        }
        if (subjectIndex >= 0) {
            return rest.drop(subjectIndex).joinToString("\n")
        }
        // 标题行已带内容却没有正文标题时，更像是用户自己写的标题，不做删除
        if (first.substring(header.range.last + 1).isNotBlank()) return text
        val blank = rest.indexOfFirst { it.isBlank() }
        return if (blank < 0) rest.joinToString("\n") else rest.drop(blank + 1).joinToString("\n")
    }

    /**
     * 去掉代码围栏。
     *
     * 首尾成对时整段剥掉外壳；只有开头一个孤立围栏（引导语被删除后才露出来，或输出被截断）
     * 时也丢弃它。围栏出现在正文中间或只有结尾一个时保持原样，那多半是正文自带的代码块。
     */
    private fun stripFence(text: String): String {
        val lines = text.lines()
        val fences = lines.indices.filter { FENCE_LINE.matches(lines[it]) }
        return when {
            fences.isEmpty() -> text
            // 只有开头一个围栏：多半是引导语被删掉后残留的，直接丢弃
            fences.size == 1 && fences[0] == 0 -> lines.drop(1).joinToString("\n")
            // 首尾成对：剥掉外壳，保留中间正文
            fences.first() == 0 && fences.last() == lines.lastIndex ->
                lines.subList(1, lines.size - 1).joinToString("\n")

            else -> text
        }
    }

    /**
     * 首行是引导语时只保留其后的正文。
     *
     * 引导语可能独占一行，也可能与正文写在同一行（`Here is the commit message: feat: xxx`），
     * 两种情形都由 [LEAD_IN_PREFIXES] 匹配前缀后取剩余部分。
     */
    private fun stripLeadIn(text: String): String {
        val breakIndex = text.indexOf('\n')
        val rawFirst = (if (breakIndex < 0) text else text.substring(0, breakIndex)).trim()
        // 模型常把引导语加粗或写成标题，去掉首尾的 Markdown 标记后再判断
        val firstLine = rawFirst.trim('*', '_', '`', '#', '>').trim()
        // 已符合 type(scope): subject 形态的行是提交信息本身，不再按引导语处理
        if (firstLine.isEmpty() || CONVENTIONAL_SUBJECT.containsMatchIn(firstLine)) return text
        val remainder = removeLeadInPrefix(firstLine) ?: return text
        // 整行都是引导语时直接丢弃；否则保留同一行的剩余正文
        val rest = if (breakIndex < 0) "" else text.substring(breakIndex + 1)
        return if (remainder.isEmpty()) rest else "$remainder\n$rest"
    }

    /** 去掉行首的引导语前缀，返回剩余正文；不是引导语时返回 null。 */
    private fun removeLeadInPrefix(line: String): String? {
        for (pattern in LEAD_IN_PREFIXES) {
            val match = pattern.find(line) ?: continue
            return line.substring(match.range.last + 1).trim(' ', ':', '：', '*', '_', '`')
        }
        return null
    }

    /** 成对思考区块：忽略大小写，允许属性与跨行。 */
    private val THINK_BLOCK = Regex(
        """<(think|thinking|analysis|reasoning|reflection)\b[^>]*>.*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 未闭合的思考区块起始标签。 */
    private val THINK_OPEN = Regex(
        """<(think|thinking|analysis|reasoning|reflection)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    /** 首行的推理标题，例如 `思考：`、`Reasoning:`。 */
    private val REASONING_HEADER = Regex(
        """^(?:思考过程|思考|推理过程|推理|分析过程|分析|reasoning|thinking|thought process)\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )

    /** Conventional Commits 的标题形态：`type(scope)!: subject`。 */
    private val CONVENTIONAL_SUBJECT = Regex("""^[A-Za-z][\w-]*(?:\([^)]*\))?!?:\s*\S""")

    /**
     * 引导语前缀。
     *
     * 吞掉不定文本的写法一律要求以冒号结尾；只有「整行就是一句寒暄或说明」的写法
     * 允许不带冒号。这样「Here is the fix for login」这类看起来像标题的句子不会被误删。
     */
    private val LEAD_IN_PREFIXES = listOf(
        Regex("""^here(?:['’]s| is| are)\b[^:：\n]*[:：]""", RegexOption.IGNORE_CASE),
        Regex("""^let me\b[^:：\n]*[:：]""", RegexOption.IGNORE_CASE),
        Regex(
            """^(?:the\s+|a\s+|an\s+)?(?:suggested\s+|proposed\s+)?(?:commit message|提交信息|提交消息)(?:\s+is)?\s*[:：]""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""^(?:以下|下面|如下)(?:是|为)[^:：\n]*[:：]"""),
        Regex("""^根据(?:以上|以下|上述)[^:：\n]*[:：]"""),
        // 限定「提交信息 / 说明」一类名词，避免误伤以「这是」开头的正常标题
        Regex("""^这是[^:：\n]*(?:提交信息|提交消息|提交说明|信息|说明|建议|结果|内容)[^:：\n]*[:：]"""),
        Regex("""^(?:sure|certainly|of course|okay|ok)\b\s*[,.:!，。！]+""", RegexOption.IGNORE_CASE),
        Regex("""^(?:好的|可以|当然)[，,：:。！!]"""),
        // 模型只回一句「Here is the commit message.」时没有正文可留，整行皆是引导语
        Regex(
            """^(?:here(?:['’]s| is| are)|this is|below is)\s+(?:the\s+|a\s+|an\s+|my\s+)?""" +
                    """(?:suggested\s+|proposed\s+|generated\s+)?(?:commit message|commit summary|summary|message)\b[^:：\n]*[.!。！]?$""",
            RegexOption.IGNORE_CASE,
        ),
    )

    /** 代码围栏行：三个及以上反引号或波浪号，可带语言标记（如 `text`、`c++`、`c#`）。 */
    private val FENCE_LINE = Regex("""^\s*(?:`{3,}|~{3,})\s*[\w.+#/-]*\s*$""")
}
