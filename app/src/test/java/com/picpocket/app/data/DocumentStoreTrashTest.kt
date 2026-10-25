package com.picpocket.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.store.DocumentStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
