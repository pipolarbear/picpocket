package com.picpocket.app.domain.workflow.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkflowCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `old cache files are removed and fresh files stay`() {
        val dir = temp.newFolder("workflow")
        val now = System.currentTimeMillis()
        val old = File(dir, "old.pdf").apply {
            writeBytes(ByteArray(4))
            setLastModified(now - WorkflowCache.MAX_AGE_MS - 1_000)
        }
        val fresh = File(dir, "fresh.pdf").apply { writeBytes(ByteArray(4)) }

        WorkflowCache.clean(dir, now)

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `the oldest files are removed when the cache is too large`() {
        val dir = temp.newFolder("workflow-size")
        val now = System.currentTimeMillis()
        val oldest = File(dir, "oldest.pdf").apply {
            writeBytes(ByteArray(10))
            setLastModified(now - 3_000)
        }
        val middle = File(dir, "middle.pdf").apply {
            writeBytes(ByteArray(10))
            setLastModified(now - 2_000)
        }
        val newest = File(dir, "newest.pdf").apply {
            writeBytes(ByteArray(10))
            setLastModified(now - 1_000)
        }

        WorkflowCache.clean(dir, now, maxBytes = 15)

        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue(newest.exists())
    }
}
