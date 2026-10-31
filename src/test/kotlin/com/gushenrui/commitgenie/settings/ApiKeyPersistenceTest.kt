package com.gushenrui.commitgenie.settings

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ApiKeyPersistenceTest {
    @Test
    fun `later save waits for an in-flight write`() = checkWrites("B")

    @Test
    fun `clear waits for an in-flight write`() = checkWrites("")

    private fun checkWrites(latest: String) {
        val executor = Executors.newFixedThreadPool(3)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val lastWritten = CountDownLatch(1)
        val stored = AtomicReference("initial")
        try {
            val settings = CommitGenieSettings({ "initial" }, { key ->
                if (key == "A") {
                    firstStarted.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                }
                stored.set(key)
                if (key == latest) lastWritten.countDown()
            }, executor)
            settings.saveApiKey("A")
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            settings.saveApiKey(latest)
            assertEquals(latest, settings.apiKey)
            // 第一笔写入尚未结束时，第二笔不得进入存储。
            assertFalse(lastWritten.await(150, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertTrue(lastWritten.await(5, TimeUnit.SECONDS))
            assertEquals(latest, stored.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `failed write does not block subsequent save`() {
        val executor = Executors.newSingleThreadExecutor()
        val saved = CountDownLatch(1)
        val stored = AtomicReference("initial")
        try {
            val settings = CommitGenieSettings({ "initial" }, { key ->
                if (key == "A") throw IllegalStateException("simulated store failure")
                stored.set(key)
                saved.countDown()
            }, executor)
            settings.saveApiKey("A")
            settings.saveApiKey("B")
            assertTrue(saved.await(5, TimeUnit.SECONDS))
            assertEquals("B", stored.get())
            assertEquals("B", settings.apiKey)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
