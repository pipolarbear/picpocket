package com.picpocket.app.data.store

import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.model.RedactionRect
import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DocumentStoreRedactionsTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var store: DocumentStore

    @Before
    fun setUp() {
        store = DocumentStore(ApplicationProvider.getApplicationContext())
    }

    private val rect = RedactionRect(0.1f, 0.2f, 0.3f, 0.4f)

    @Test
    fun `marked areas round-trip through the metadata file`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.appendPage(doc.id, "page1.jpg", fileSizeBytes = 10L)

        store.updatePageRedactions(doc.id, 1, listOf(rect)).getOrThrow()

        val stored = store.readMetadata(doc.id).getOrThrow().pages.first { it.pageNumber == 1 }
        assertEquals(1, stored.redactions.size)
        assertEquals(rect.x, stored.redactions.first().x, 0.0001f)
        assertEquals(rect.h, stored.redactions.first().h, 0.0001f)
    }

    @Test
    fun `marking areas bumps the metadata version`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.appendPage(doc.id, "page1.jpg", fileSizeBytes = 10L)
        val before = store.metadataVersion(doc.id)

        store.updatePageRedactions(doc.id, 1, listOf(rect)).getOrThrow()

        assertTrue(store.metadataVersion(doc.id) > before)
    }

    @Test
    fun `tiny and out-of-range areas are stored clamped and usable only`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.appendPage(doc.id, "page1.jpg", fileSizeBytes = 10L)

        store.updatePageRedactions(
            doc.id,
            1,
            listOf(
                RedactionRect(0f, 0f, 0.001f, 0.001f),
                RedactionRect(-0.2f, 0.5f, 1.5f, 0.2f),
            ),
        ).getOrThrow()

        val stored = store.readMetadata(doc.id).getOrThrow().pages.first()
        assertEquals(1, stored.redactions.size)
        assertEquals(0f, stored.redactions.first().x, 0.0001f)
        assertEquals(1f, stored.redactions.first().w, 0.0001f)
    }

    @Test
    fun `clearing an area removes it`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.appendPage(doc.id, "page1.jpg", fileSizeBytes = 10L)
        store.updatePageRedactions(doc.id, 1, listOf(rect)).getOrThrow()

        store.updatePageRedactions(doc.id, 1, emptyList()).getOrThrow()

        assertTrue(store.readMetadata(doc.id).getOrThrow().pages.first().redactions.isEmpty())
    }

    @Test
    fun `reordering pages keeps each page's areas with the page`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.appendPage(doc.id, "page1.jpg", fileSizeBytes = 10L)
        store.appendPage(doc.id, "page2.jpg", fileSizeBytes = 10L)
        store.updatePageRedactions(doc.id, 2, listOf(rect)).getOrThrow()

        store.reorderPages(doc.id, listOf(2, 1)).getOrThrow()

        val pages = store.readMetadata(doc.id).getOrThrow().pages
        assertEquals("page2.jpg", pages.first().filename)
        assertEquals(1, pages.first().redactions.size)
    }

    @Test
    fun `removing another page keeps the marked areas`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.appendPage(doc.id, "page1.jpg", fileSizeBytes = 10L)
        store.appendPage(doc.id, "page2.jpg", fileSizeBytes = 10L)
        store.updatePageRedactions(doc.id, 1, listOf(rect)).getOrThrow()

        store.removePage(doc.id, 2).getOrThrow()

        assertEquals(1, store.readMetadata(doc.id).getOrThrow().pages.first().redactions.size)
    }
}
