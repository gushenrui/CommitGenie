package com.gushenrui.commitgenie.git

import com.intellij.openapi.diff.impl.patch.PatchHunk
import com.intellij.openapi.diff.impl.patch.PatchLine
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class DiffBuilderTest {
    private val base = Path.of("/repo")

    private fun hunk(start: Int, before: String, after: String) =
        PatchHunk(start, start + 1, start, start + 1).apply {
            addLine(PatchLine(PatchLine.Type.REMOVE, before))
            addLine(PatchLine(PatchLine.Type.ADD, after))
        }

    private fun patch(vararg hunks: PatchHunk, name: String = "file.txt") =
        TextFilePatch(Charsets.UTF_8).apply {
            beforeName = name
            afterName = name
            hunks.forEach(::addHunk)
        }

    private fun render(patch: TextFilePatch, limit: Int = 0) =
        DiffBuilder.renderPatches(base, sequenceOf(patch), limit)

    @Test
    fun `complete hunk has correct ranges and content`() {
        val result = render(patch(hunk(0, "old", "new")))
        assertEquals(
            "diff --git a/file.txt b/file.txt\n--- a/file.txt\n+++ b/file.txt\n@@ -1,1 +1,1 @@\n-old\n+new\n",
            result.text
        )
        assertFalse(result.truncated)
    }

    @Test
    fun `first hunk that does not fit leaves no orphan header`() {
        val patch = patch(hunk(0, "old", "x".repeat(2000)))
        val result = render(patch, 1000)
        assertEquals("", result.text)
        assertTrue(result.truncated)
    }

    @Test
    fun `later hunk that does not fit leaves earlier hunk intact`() {
        val first = hunk(0, "old", "new")
        val expected = render(patch(first)).text
        val result =
            render(patch(first, hunk(20, "before", "after".repeat(100))), expected.length + 40)
        assertEquals(expected, result.text)
        assertTrue(result.truncated)
    }

    @Test
    fun `exact limit is not reported as truncation`() {
        val patch = patch(hunk(0, "old", "new"))
        val full = render(patch)
        assertEquals(full, render(patch, full.text.length))
    }

    @Test
    fun `rejected hunk stops construction of remaining patches`() {
        var constructed = 0
        val patches = sequence {
            constructed++
            yield(patch(hunk(0, "old", "x".repeat(2000))))
            constructed++
            yield(patch(hunk(0, "a", "b"), name = "other.txt"))
        }
        assertTrue(DiffBuilder.renderPatches(base, patches, 1000).truncated)
        assertEquals(1, constructed)
    }

    @Test
    fun `next file does not leave a partial file header`() {
        val first = patch(hunk(0, "a", "b"))
        val expected = render(first).text
        val result = DiffBuilder.renderPatches(
            base,
            sequenceOf(first, patch(hunk(0, "x", "y"), name = "next.txt")),
            expected.length + 10
        )
        assertEquals(expected, result.text)
        assertTrue(result.truncated)
    }

    @Test
    fun `emoji and no-newline marker are retained atomically`() {
        val hunk = hunk(0, "old", "😀")
        hunk.lines.last().isSuppressNewLine = true
        val patch = patch(hunk)
        val full = render(patch)
        assertTrue(full.text.endsWith("+😀\n\\ No newline at end of file\n"))
        assertEquals(full, render(patch, full.text.length))
        val short = render(patch, full.text.length - 1)
        assertEquals("", short.text)
        assertTrue(short.truncated)
    }

    @Test
    fun `empty file metadata can be emitted without hunks`() {
        val result = render(patch())
        assertTrue(result.text.startsWith("diff --git"))
        assertFalse(result.truncated)
    }

    @Test
    fun `zero and negative limits are unlimited`() {
        val patch = patch(hunk(0, "a", "b"))
        assertEquals(render(patch, 0), render(patch, -1))
    }
}
