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

    data class BuiltDiff(val text: String, val truncated: Boolean)

    suspend fun buildDiff(project: Project, changes: List<Change>, maxChars: Int): BuiltDiff =
        withContext(Dispatchers.IO) {
            val baseDir = PatchWriter.calculateBaseDirForWritingPatch(project, changes)
            val builder = CreatePatchCommitExecutor.DefaultPatchBuilder(project)
            // 惰性构建：首个放不下的 hunk 出现后，不再读取后续文件。
            val patches = sequence {
                for (change in changes) {
                    val built = try {
                        builder.buildPatches(
                            baseDir,
                            constrainBinaryChanges(listOf(change)),
                            false,
                            true
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("构建变更补丁失败: ${change.virtualFile?.path ?: change}", e)
                        emptyList()
                    }
                    yieldAll(built)
                }
            }
            renderPatches(baseDir, patches, maxChars)
        }

    internal fun renderPatches(
        baseDir: java.nio.file.Path,
        patches: Sequence<FilePatch>,
        maxChars: Int,
    ): BuiltDiff {
        val out = LimitedText(if (maxChars > 0) maxChars else Int.MAX_VALUE)
        for (patch in patches) {
            render(baseDir, patch, out)
            if (out.truncated) break
        }
        return BuiltDiff(out.toString(), out.truncated)
    }

    private fun render(
        baseDir: java.nio.file.Path,
        patch: FilePatch,
        out: LimitedText
    ) {
        // 版本号对提交信息没有帮助，去掉可减少噪声
        patch.beforeVersionId = null
        patch.afterVersionId = null
        when (patch) {
            is TextFilePatch -> renderTextPatch(baseDir, patch, out)
            is BinaryFilePatch -> renderBinaryPatch(baseDir, patch, out)
        }
    }

    private fun renderTextPatch(
        baseDir: java.nio.file.Path,
        patch: TextFilePatch,
        out: LimitedText,
    ) {
        // 新增/删除文件只有一侧有名字，diff --git 行两侧都用已知的那个
        val beforePath = relativePath(baseDir, patch.beforeName ?: patch.afterName)
        val afterPath = relativePath(baseDir, patch.afterName ?: patch.beforeName)
        val fileHeader = buildString {
            append("diff --git a/").append(beforePath).append(" b/").append(afterPath).append('\n')
            append("--- ").append(if (patch.isNewFile) DEV_NULL else "a/$beforePath").append('\n')
            append("+++ ").append(if (patch.isDeletedFile) DEV_NULL else "b/$afterPath")
                .append('\n')
        }
        if (patch.hunks.isEmpty()) {
            // 空文件新增/删除、纯重命名可以只有文件头。
            out.appendIfFits(fileHeader)
            return
        }
        for ((index, hunk) in patch.hunks.withIndex()) {
            // 文件头与首个 hunk 一起提交；后续 hunk 也必须完整放下。
            val unit = LimitedText(out.remaining)
            if (index == 0) unit.appendIfFits(fileHeader)
            unit.appendIfFits(
                "@@ -${range(hunk.startLineBefore, hunk.endLineBefore)}" +
                        " +${range(hunk.startLineAfter, hunk.endLineAfter)} @@\n"
            )
            for (line in hunk.lines) {
                if (unit.truncated) break
                val text = buildString {
                    append(prefix(line.type)).append(line.text)
                    if (line.isSuppressNewLine) append('\n').append(NO_NEWLINE_SIGNATURE)
                    append('\n')
                }
                unit.appendIfFits(text)
            }
            if (unit.truncated) {
                out.truncated = true
                return
            }
            out.appendIfFits(unit.toString())
        }
    }

    private fun renderBinaryPatch(
        baseDir: java.nio.file.Path,
        patch: BinaryFilePatch,
        out: LimitedText,
    ) {
        val writer = StringWriter()
        GitPatchWriter.writeGitHeader(writer, baseDir, patch, "\n")
        val content = buildString {
            append(writer)
            append("index ").append(sha1(patch.beforeContent))
                .append("..").append(sha1(patch.afterContent)).append("\n\n")
        }
        out.appendIfFits(content)
    }

    private fun prefix(type: PatchLine.Type): Char = when (type) {
        PatchLine.Type.CONTEXT -> ' '
        PatchLine.Type.ADD -> '+'
        PatchLine.Type.REMOVE -> '-'
    }

    /** 空范围按 unified diff 规范写成 `start,0`，start 为插入点之前的行号。 */
    private fun range(start: Int, end: Int): String =
        if (start == end) "$start,0" else "${start + 1},${end - start}"

    /**
     * 把 patch 中的文件名换算成相对 [baseDir] 的路径。
     *
     * patch 由 [baseDir] 构建，相对路径也应以它为基准解析；若改用 project.basePath，
     * 当勾选的变更只在某个模块或子目录内时，diff 里的 a/、b/ 路径会多出或缺少前缀。
     */
    private fun relativePath(baseDir: java.nio.file.Path, name: String?): String {
        val path = name ?: return ""
        return runCatching {
            val source = java.nio.file.Paths.get(path)
            val absolute = if (source.isAbsolute) source else baseDir.resolve(source)
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

        var truncated = false
        val remaining: Int get() = limit - buffer.length

        /** 拒绝一次追加后即停止；从不输出半行或半个原子单元。 */
        fun appendIfFits(value: String): Boolean {
            if (truncated) return false
            if (value.length > remaining) {
                truncated = true
                return false
            }
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
