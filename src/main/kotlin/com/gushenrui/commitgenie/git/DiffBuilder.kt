package com.gushenrui.commitgenie.git

import com.intellij.openapi.diff.impl.patch.BinaryFilePatch
import com.intellij.openapi.diff.impl.patch.FilePatch
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.PatchLine
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.patch.BlobIndexUtil
import com.intellij.openapi.vcs.changes.patch.CreatePatchCommitExecutor
import com.intellij.openapi.vcs.changes.patch.GitPatchWriter
import com.intellij.openapi.vcs.changes.patch.PatchWriter
import com.intellij.openapi.vfs.limits.FileSizeLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.StringWriter

/**
 * 把勾选的变更渲染成标准 unified diff 文本。
 *
 * 逐文件构建 patch：单个文件失败不会中断整体流程，只会被跳过。
 * 平台的 UnifiedDiffWriter 标记为 @ApiStatus.Internal，因此文本渲染在这里自行完成。
 */
object DiffBuilder {

    suspend fun buildDiff(project: Project, changes: List<Change>, maxChars: Int): String =
        withContext(Dispatchers.IO) {
            val baseDir = PatchWriter.calculateBaseDirForWritingPatch(project, changes)
            val builder = CreatePatchCommitExecutor.DefaultPatchBuilder(project)
            // maxChars <= 0 表示不限制，与 truncateByCodePoint 的约定保持一致
            val out = LimitedText(if (maxChars > 0) maxChars else Int.MAX_VALUE)
            var skipped = 0

            for (change in constrainBinaryChanges(changes)) {
                // 已达到上限时不再构建后续 patch，避免继续读取并渲染大文件
                if (out.isFull) {
                    skipped++
                    continue
                }
                try {
                    builder.buildPatches(baseDir, listOf(change), false, true)
                        .forEach { patch -> render(project, baseDir, patch, out) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("构建变更补丁失败: ${change.virtualFile?.path ?: change}", e)
                }
            }

            if (skipped > 0) {
                LOG.info("diff 已达到 $maxChars 字符上限，跳过 $skipped 个文件")
            }
            // 收尾保险：整行追加不会劈开代理对，这里只兜住外部传入的异常上限
            truncateByCodePoint(out.toString(), maxChars)
        }

    /**
     * 按码点截断，避免把代理对（如 emoji）劈成孤立代理。
     *
     * 孤立代理无法编码为合法 UTF-8，会导致服务端解析 JSON 失败并返回 400。
     * 渲染阶段已按上限停止，这里同时兜住「恰好停在代理对中间」的边界。
     */
    private fun truncateByCodePoint(text: String, maxChars: Int): String {
        if (maxChars <= 0) return text
        var end = minOf(text.length, maxChars)
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) {
            end--
        }
        return if (end == text.length) text else text.substring(0, end)
    }

    private fun render(
        project: Project,
        baseDir: java.nio.file.Path,
        patch: FilePatch,
        out: LimitedText
    ) {
        // 版本号对提交信息没有帮助，去掉可减少噪声
        patch.beforeVersionId = null
        patch.afterVersionId = null
        when (patch) {
            is TextFilePatch -> renderTextPatch(project, baseDir, patch, out)
            is BinaryFilePatch -> renderBinaryPatch(baseDir, patch, out)
        }
    }

    private fun renderTextPatch(
        project: Project,
        baseDir: java.nio.file.Path,
        patch: TextFilePatch,
        out: LimitedText,
    ) {
        // 新增/删除文件只有一侧有名字，diff --git 行两侧都用已知的那个
        val beforePath = relativePath(project, baseDir, patch.beforeName ?: patch.afterName)
        val afterPath = relativePath(project, baseDir, patch.afterName ?: patch.beforeName)
        val fileHeader = buildString {
            append("diff --git a/").append(beforePath).append(" b/").append(afterPath).append('\n')
            append("--- ").append(if (patch.isNewFile) DEV_NULL else "a/$beforePath").append('\n')
            append("+++ ").append(if (patch.isDeletedFile) DEV_NULL else "b/$afterPath")
                .append('\n')
        }
        // 连文件头都放不下时直接结束，不留下无主的片段
        if (!out.appendIfFits(fileHeader)) return
        for (hunk in patch.hunks) {
            // 整块 hunk 放不下时不写残缺头部，避免模型读到结构不完整的 diff
            val header = "@@ -${range(hunk.startLineBefore, hunk.endLineBefore)}" +
                    " +${range(hunk.startLineAfter, hunk.endLineAfter)} @@\n"
            if (!out.appendIfFits(header)) return
            for (line in hunk.lines) {
                val text = buildString {
                    append(prefix(line.type)).append(line.text)
                    if (line.isSuppressNewLine) {
                        append('\n').append(NO_NEWLINE_SIGNATURE)
                    }
                    append('\n')
                }
                // 单行放不下说明已到上限，直接结束，保证输出停在完整行
                if (!out.appendIfFits(text)) return
            }
        }
        out.appendIfFits("\n")
    }

    private fun renderBinaryPatch(
        baseDir: java.nio.file.Path,
        patch: BinaryFilePatch,
        out: LimitedText,
    ) {
        val writer = StringWriter()
        GitPatchWriter.writeGitHeader(writer, baseDir, patch, "\n")
        // 二进制补丁的头部同样整段写入，放不下就停止，避免后续哈希白算
        if (!out.appendIfFits(writer.toString())) return
        val index = buildString {
            append("index ").append(sha1(patch.beforeContent))
                .append("..").append(sha1(patch.afterContent)).append("\n\n")
        }
        out.appendIfFits(index)
    }

    private fun prefix(type: PatchLine.Type): Char = when (type) {
        PatchLine.Type.CONTEXT -> ' '
        PatchLine.Type.ADD -> '+'
        PatchLine.Type.REMOVE -> '-'
    }

    /** 空范围按 unified diff 规范写成 `start,0`，start 为插入点之前的行号。 */
    private fun range(start: Int, end: Int): String =
        if (start == end) "$start,0" else "${start + 1},${end - start}"

    private fun relativePath(project: Project, baseDir: java.nio.file.Path, name: String?): String {
        val path = name ?: return ""
        return runCatching {
            val absolute = if (java.nio.file.Paths.get(path).isAbsolute) {
                java.nio.file.Paths.get(path)
            } else {
                (project.basePath?.let { java.nio.file.Paths.get(it) } ?: baseDir).resolve(path)
            }
            baseDir.relativize(absolute).toString().replace('\\', '/')
        }.getOrDefault(path.replace('\\', '/'))
    }

    /**
     * 二进制内容按文件大小上限截断后再计算哈希。
     *
     * 超大文件若整体读入内存会拖垮 IDE，这里沿用平台的加载上限。
     */
    private fun constrainBinaryChanges(changes: List<Change>): List<Change> =
        changes.map { change ->
            val after = change.afterRevision
            if (after is ByteBackedContentRevision && IdeaTextPatchBuilder.isBinaryRevision(after)) {
                Change(change.beforeRevision, ConstrainedBinaryRevision(after), change.fileStatus)
            } else {
                change
            }
        }

    private fun sha1(content: ByteArray?): String =
        if (content == null) BlobIndexUtil.NOT_COMMITTED_HASH else BlobIndexUtil.getSha1(content)

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DiffBuilder::class.java)

    /**
     * 边渲染边限长的缓冲区。
     *
     * 达到上限后不再接收内容，渲染循环据此提前退出，避免完整 diff 常驻内存。
     */
    private class LimitedText(private val limit: Int) {
        private val buffer = StringBuilder(INITIAL_CAPACITY)

        /** 仅在有限额且已用完时视为已满；不限额时始终可继续追加。 */
        val isFull: Boolean get() = limit != Int.MAX_VALUE && buffer.length >= limit

        /** 仅当整段内容放得下时才追加，保证截断处不会留下半行。 */
        fun appendIfFits(value: String): Boolean {
            if (value.isEmpty()) return !isFull
            if (limit != Int.MAX_VALUE && buffer.length + value.length > limit) return false
            buffer.append(value)
            return true
        }

        override fun toString(): String = buffer.toString()

        private companion object {
            const val INITIAL_CAPACITY = 8_192
        }
    }

    private class ConstrainedBinaryRevision(
        private val delegate: ByteBackedContentRevision,
    ) : ByteBackedContentRevision {

        override fun getContent(): String? = delegate.content

        override fun getFile() = delegate.file

        override fun getRevisionNumber() = delegate.revisionNumber

        override fun getContentAsBytes(): ByteArray? {
            val file = delegate.file.virtualFile ?: return null
            val limit = FileSizeLimit.getContentLoadLimit(file.extension.orEmpty())
            if (file.length <= limit) return delegate.contentAsBytes

            return runCatching { file.inputStream.use { it.readNBytes(limit) } }.getOrNull()
        }
    }

    private const val NO_NEWLINE_SIGNATURE = "\\ No newline at end of file"
    private const val DEV_NULL = "/dev/null"
}
