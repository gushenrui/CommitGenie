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
            val patches = mutableListOf<FilePatch>()

            for (change in constrainBinaryChanges(changes)) {
                try {
                    patches += builder.buildPatches(baseDir, listOf(change), false, true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("构建变更补丁失败: ${change.virtualFile?.path ?: change}", e)
                }
            }

            if (patches.isEmpty()) return@withContext ""

            val diff = render(project, baseDir, patches)
            truncateByCodePoint(diff, maxChars)
        }

    /**
     * 按码点截断，避免把代理对（如 emoji）劈成孤立代理。
     *
     * 孤立代理无法编码为合法 UTF-8，会导致服务端解析 JSON 失败并返回 400。
     */
    private fun truncateByCodePoint(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        var end = maxChars
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) {
            end--
        }
        return text.substring(0, end)
    }

    private fun render(project: Project, baseDir: java.nio.file.Path, patches: List<FilePatch>): String {
        val out = StringBuilder()
        patches.forEach { patch ->
            // 版本号对提交信息没有帮助，去掉可减少噪声
            patch.beforeVersionId = null
            patch.afterVersionId = null
            when (patch) {
                is TextFilePatch -> renderTextPatch(project, baseDir, patch, out)
                is BinaryFilePatch -> renderBinaryPatch(baseDir, patch, out)
            }
        }
        return out.toString()
    }

    private fun renderTextPatch(
        project: Project,
        baseDir: java.nio.file.Path,
        patch: TextFilePatch,
        out: StringBuilder,
    ) {
        out.append("diff --git a/")
            .append(relativePath(project, baseDir, patch.beforeName))
            .append(" b/")
            .append(relativePath(project, baseDir, patch.afterName))
            .append('\n')
        out.append("--- ")
            .append(fileLabel(project, baseDir, patch.beforeName, patch.isNewFile))
            .append('\n')
        out.append("+++ ")
            .append(fileLabel(project, baseDir, patch.afterName, patch.isDeletedFile))
            .append('\n')
        patch.hunks.forEach { hunk ->
            out.append("@@ -")
                .append(range(hunk.startLineBefore, hunk.endLineBefore))
                .append(" +")
                .append(range(hunk.startLineAfter, hunk.endLineAfter))
                .append(" @@\n")
            hunk.lines.forEach { line ->
                out.append(prefix(line.type)).append(line.text)
                if (line.isSuppressNewLine) {
                    out.append('\n').append(NO_NEWLINE_SIGNATURE)
                }
                out.append('\n')
            }
        }
        out.append('\n')
    }

    private fun renderBinaryPatch(
        baseDir: java.nio.file.Path,
        patch: BinaryFilePatch,
        out: StringBuilder,
    ) {
        val writer = StringWriter()
        GitPatchWriter.writeGitHeader(writer, baseDir, patch, "\n")
        out.append(writer)
        out.append("index ")
            .append(sha1(patch.beforeContent))
            .append("..")
            .append(sha1(patch.afterContent))
            .append("\n\n")
    }

    private fun prefix(type: PatchLine.Type): Char = when (type) {
        PatchLine.Type.CONTEXT -> ' '
        PatchLine.Type.ADD -> '+'
        PatchLine.Type.REMOVE -> '-'
    }

    private fun range(start: Int, end: Int): String =
        if (start == end) "$start,1" else "${start + 1},${end - start}"

    private fun fileLabel(
        project: Project,
        baseDir: java.nio.file.Path,
        name: String?,
        deleted: Boolean,
    ): String {
        if (name == null) return "/dev/null"
        val prefix = if (deleted) "a/" else "b/"
        return prefix + relativePath(project, baseDir, name)
    }

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
}
