package com.picpocket.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.store.DocumentStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DocumentStoreTrashTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = DocumentStore(app)

    @Before
    fun setUp() {
        File(app.filesDir, "documents").deleteRecursively()
        File(app.filesDir, "trash").deleteRecursively()
        File(app.cacheDir, "workflow").deleteRecursively()
    }

    @Test
    fun `a workflow delete moves the document to trash and restores it`() = runTest {
        val doc = store.createDocument("Receipt").getOrThrow()

        store.moveToTrash(doc.id).getOrThrow()

        assertTrue(store.listDocuments().getOrThrow().isEmpty())
        val trashed = store.listTrash().getOrThrow().single()
        assertEquals(doc.id, trashed.id)
        assertEquals("Receipt", trashed.name)

        store.restoreFromTrash(doc.id).getOrThrow()

        assertTrue(store.listTrash().getOrThrow().isEmpty())
        assertEquals(1, store.listDocuments().getOrThrow().size)
    }

    @Test
    fun `purge only removes entries older than the retention`() = runTest {
        val old = store.createDocument("Old").getOrThrow()
        store.moveToTrash(old.id).getOrThrow()
        File(app.filesDir, "trash/${old.id}")
            .setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)

        val fresh = store.createDocument("Fresh").getOrThrow()
        store.moveToTrash(fresh.id).getOrThrow()

        assertEquals(1, store.purgeTrash(7))
        val remaining = store.listTrash().getOrThrow().single()
        assertEquals(fresh.id, remaining.id)
    }

    @Test
    fun `deleting trashed data is permanent`() = runTest {
        val doc = store.createDocument("Receipt").getOrThrow()
        store.moveToTrash(doc.id).getOrThrow()

        store.deleteTrashed(doc.id).getOrThrow()

        assertTrue(store.listTrash().getOrThrow().isEmpty())
        assertTrue(store.restoreFromTrash(doc.id).isFailure)
    }

    @Test
    fun `permanent delete removes only that document's workflow artifacts`() = runTest {
        val doc = store.createDocument("Receipt").getOrThrow()
        val other = store.createDocument("Other").getOrThrow()
        val dir = File(app.cacheDir, "workflow").apply { mkdirs() }
        val pdf = File(dir, "${doc.id}.pdf").apply { writeBytes(byteArrayOf(1)) }
        val encrypted = File(dir, "${doc.id}.enc").apply { writeBytes(byteArrayOf(1)) }
        val otherPdf = File(dir, "${other.id}.pdf").apply { writeBytes(byteArrayOf(1)) }

        store.deleteDocument(doc.id).getOrThrow()

        assertFalse(pdf.exists())
        assertFalse(encrypted.exists())
        assertTrue("another document's artifact must stay", otherPdf.exists())
    }

    @Test
    fun `moving to trash keeps the artifact until it is deleted permanently`() = runTest {
        val doc = store.createDocument("Receipt").getOrThrow()
        val pdf = File(File(app.cacheDir, "workflow").apply { mkdirs() }, "${doc.id}.pdf")
            .apply { writeBytes(byteArrayOf(1)) }

        store.moveToTrash(doc.id).getOrThrow()
        assertTrue("trash keeps the artifact", pdf.exists())

        store.deleteTrashed(doc.id).getOrThrow()
        assertFalse(pdf.exists())
    }

    @Test
    fun `purging expired trash removes their artifacts`() = runTest {
        val expired = store.createDocument("Expired").getOrThrow()
        store.moveToTrash(expired.id).getOrThrow()
        File(app.filesDir, "trash/${expired.id}")
            .setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        val dir = File(app.cacheDir, "workflow").apply { mkdirs() }
        val expiredPdf = File(dir, "${expired.id}.pdf").apply { writeBytes(byteArrayOf(1)) }

        val fresh = store.createDocument("Fresh").getOrThrow()
        store.moveToTrash(fresh.id).getOrThrow()
        val freshPdf = File(dir, "${fresh.id}.pdf").apply { writeBytes(byteArrayOf(1)) }

        assertEquals(1, store.purgeTrash(7))

        assertFalse(expiredPdf.exists())
        assertTrue(freshPdf.exists())
    }
}
