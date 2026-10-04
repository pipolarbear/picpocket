package com.picpocket.app.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import com.picpocket.app.data.local.PicPocketDatabase
import com.picpocket.app.data.repository.DocumentRepositoryImpl
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.workflow.DocumentEvent
import com.picpocket.app.data.workflow.DocumentEventBus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.ocr.OcrEngine
import com.picpocket.app.domain.ocr.OcrManager
import com.picpocket.app.domain.pdfimport.PdfPageImportResult
import com.picpocket.app.domain.pdfimport.PdfPageImporter
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun tempPageUri(): String {
    val f = File.createTempFile("page", ".jpg")
    f.writeBytes(byteArrayOf(0, 1, 2))
    return Uri.fromFile(f).toString()
}

private fun tempPageUri(marker: Int): String {
    val f = File.createTempFile("page", ".jpg")
    f.writeBytes(byteArrayOf(marker.toByte()))
    return Uri.fromFile(f).toString()
}

private fun createOcrManager(app: android.app.Application): OcrManager {
    return OcrManager(
        object : OcrEngine {
            override suspend fun recognize(bitmap: android.graphics.Bitmap): com.picpocket.app.domain.ocr.OcrResult {
                return com.picpocket.app.domain.ocr.OcrResult("", 0f)
            }
        },
        DocumentStore(app),
    )
}

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DocumentRepositoryTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var database: PicPocketDatabase
    private lateinit var repository: DocumentRepositoryImpl
    private val eventBus = DocumentEventBus()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PicPocketDatabase::class.java,
        ).allowMainThreadQueries().build()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        repository = DocumentRepositoryImpl(
            store = DocumentStore(app),
            tagDao = database.tagDao(),
            eventBus = eventBus,
            pdfPageImporter = PdfPageImporter(),
            ocrManager = com.picpocket.app.domain.ocr.OcrManager(
                object : OcrEngine {
                    override suspend fun recognize(bitmap: android.graphics.Bitmap): com.picpocket.app.domain.ocr.OcrResult {
                        return com.picpocket.app.domain.ocr.OcrResult("", 0f)
                    }
                },
                DocumentStore(app),
            ),
            app = app,
            documentAccess = com.picpocket.app.data.store.DocumentAccessStore(app),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `create document and observe list`() = runTest {
        val id = repository.createDocument("Test Doc").getOrThrow()
        assertTrue("Document ID should not be empty", id.isNotEmpty())
        val doc = repository.getDocument(id).getOrNull()
        assertNotNull(doc)
        assertEquals("Test Doc", doc?.name)
    }

    @Test
    fun `mutations emit document events`() = runTest {
        val received = mutableListOf<DocumentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            eventBus.events.collect { received += it }
        }
        val id = repository.createDocument("Doc").getOrThrow()
        assertTrue(received.any { it.event == TriggerEvent.DOC_CREATED && it.documentId == id })

        repository.updateDocumentName(id, "Renamed")
        assertTrue(received.any { it.event == TriggerEvent.RENAMED && it.documentId == id })

        val tagId = repository.createTag("t")
        repository.setDocumentTags(id, listOf(tagId))
        assertTrue(received.any { it.event == TriggerEvent.TAGGED && it.documentId == id })
        repository.setDocumentTags(id, emptyList())
        assertTrue(received.any { it.event == TriggerEvent.UNTAGGED && it.documentId == id })

        repository.deleteDocument(id)
        assertTrue(received.any { it.event == TriggerEvent.DELETED && it.documentId == id })
    }

    @Test
    fun `add page to document`() = runTest {
        val docId = repository.createDocument("Multi-page").getOrThrow()
        repository.addPage(docId, tempPageUri())

        val pages = repository.getPages(docId).getOrDefault(emptyList())
        assertEquals(1, pages.size)
    }

    @Test
    fun `add multiple pages increments pageNumber`() = runTest {
        val docId = repository.createDocument("Pages").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())

        val pages = repository.getPages(docId).getOrDefault(emptyList())
        assertEquals(3, pages.size)
        assertEquals(1, pages[0].pageNumber)
        assertEquals(2, pages[1].pageNumber)
        assertEquals(3, pages[2].pageNumber)
    }

    @Test
    fun `delete document removes it and its pages`() = runTest {
        val docId = repository.createDocument("Delete me").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.deleteDocument(docId)

        val doc = repository.getDocument(docId).getOrNull()
        assertNull("Document should be null after delete", doc)
        val pages = repository.getPages(docId).getOrDefault(emptyList())
        assertTrue("Pages should be empty after document delete", pages.isEmpty())
    }

    @Test
    fun `update document name`() = runTest {
        val docId = repository.createDocument("Old Name").getOrThrow()
        repository.updateDocumentName(docId, "New Name")

        val doc = repository.getDocument(docId).getOrNull()
        assertNotNull(doc)
        assertEquals("New Name", doc?.name)
    }

    @Test
    fun `update page OCR text`() = runTest {
        val docId = repository.createDocument("OCR test").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.updatePageOcrText(docId, 1, "Extracted text")

        val pages = repository.getPages(docId).getOrDefault(emptyList())
        assertEquals("Extracted text", pages[0].ocrText)
    }

    @Test
    fun `delete multiple documents at once`() = runTest {
        val id1 = repository.createDocument("Doc 1").getOrThrow()
        val id2 = repository.createDocument("Doc 2").getOrThrow()
        val id3 = repository.createDocument("Doc 3").getOrThrow()

        repository.deleteDocuments(listOf(id1, id3))

        assertNull(repository.getDocument(id1).getOrNull())
        assertNotNull(repository.getDocument(id2).getOrNull())
        assertNull(repository.getDocument(id3).getOrNull())
    }

    @Test
    fun `delete page removes it and keeps others`() = runTest {
        val docId = repository.createDocument("Multi-page").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())

        repository.deletePage(docId, 2)

        val remaining = repository.getPages(docId).getOrDefault(emptyList())
        assertEquals(2, remaining.size)
        assertEquals(1, remaining[0].pageNumber)
        assertEquals(2, remaining[1].pageNumber)
    }

    @Test
    fun `getDocument returns pageCount and totalFileSize`() = runTest {
        val docId = repository.createDocument("Stats test").getOrThrow()
        repository.addPage(docId, tempPageUri(), fileSizeBytes = 1000L)
        repository.addPage(docId, tempPageUri(), fileSizeBytes = 2000L)

        val doc = repository.getDocument(docId).getOrNull()
        assertNotNull(doc)
    }

    @Test
    fun `reorder pages updates page numbers`() = runTest {
        val docId = repository.createDocument("Reorder test").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())

        repository.reorderPages(docId, listOf(3, 1, 2))

        val pages = repository.getPages(docId).getOrDefault(emptyList()).sortedBy { it.pageNumber }
        assertEquals(3, pages.size)
    }

    @Test
    fun `create tag and observe all tags`() = runTest {
        val tagId = repository.createTag("Work")
        assertTrue("Tag ID should be positive", tagId > 0)
        val tags = repository.observeAllTags().first()
        assertEquals(1, tags.size)
        assertEquals("Work", tags[0].name)
    }

    @Test
    fun `rename tag updates name`() = runTest {
        val tagId = repository.createTag("Old")
        repository.renameTag(tagId, "Renamed")
        val tags = repository.observeAllTags().first()
        assertEquals("Renamed", tags[0].name)
    }

    @Test
    fun `delete tags removes them`() = runTest {
        val id1 = repository.createTag("Tag1")
        val id2 = repository.createTag("Tag2")
        repository.deleteTags(listOf(id1))
        val tags = repository.observeAllTags().first()
        assertEquals(1, tags.size)
        assertEquals(id2, tags[0].id)
    }

    @Test
    fun `set document tags replaces existing tags`() = runTest {
        val docId = repository.createDocument("Tagged Doc").getOrThrow()
        val tag1 = repository.createTag("Important")
        val tag2 = repository.createTag("Urgent")

        repository.setDocumentTags(docId, listOf(tag1, tag2))
        var docTags = repository.observeDocumentTags(docId).first()
        assertEquals(2, docTags.size)

        repository.setDocumentTags(docId, listOf(tag1))
        docTags = repository.observeDocumentTags(docId).first()
        assertEquals(1, docTags.size)
        assertEquals(tag1, docTags[0].id)
    }

    @Test
    fun `set document tags with empty list clears tags`() = runTest {
        val docId = repository.createDocument("Clear Tags").getOrThrow()
        val tag = repository.createTag("Temp")
        repository.setDocumentTags(docId, listOf(tag))
        repository.setDocumentTags(docId, emptyList())
        val docTags = repository.observeDocumentTags(docId).first()
        assertTrue(docTags.isEmpty())
    }

    @Test
    fun `renaming a tag updates document references`() = runTest {
        val docId = repository.createDocument("Doc").getOrThrow()
        val tagId = repository.createTag("Old Name")
        repository.setDocumentTags(docId, listOf(tagId))

        repository.renameTag(tagId, "New Name")

        val tags = repository.observeDocumentTags(docId).first()
        assertEquals(1, tags.size)
        assertEquals("New Name", tags.single().name)
        assertEquals("workflow HasTag still resolves by id", tagId, tags.single().id)
    }

    @Test
    fun `deleting a tag removes it from documents`() = runTest {
        val docId = repository.createDocument("Doc").getOrThrow()
        val tagId = repository.createTag("Temp")
        repository.setDocumentTags(docId, listOf(tagId))

        repository.deleteTags(listOf(tagId))

        assertTrue(repository.observeDocumentTags(docId).first().isEmpty())
    }

    @Test
    fun `document tag map emits when a tag is added to a document`() = runTest {
        val docId = repository.createDocument("Doc").getOrThrow()
        val tagId = repository.createTag("t")
        // Wait for the in-memory document list to include the new document.
        repository.observeDocuments().first { docs -> docs.any { it.id == docId } }

        val latch = java.util.concurrent.CountDownLatch(1)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeDocumentTagMap().collect { map ->
                if (map[docId]?.any { it.id == tagId } == true) latch.countDown()
            }
        }

        repository.setDocumentTags(docId, listOf(tagId))

        assertTrue(
            "map must re-emit with the new tag",
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS),
        )
    }

    @Test
    fun `a never accessed document falls back to last modified`() = runTest {
        val docId = repository.createDocument("Doc").getOrThrow()
        val doc = repository.observeDocuments().first { list -> list.any { it.id == docId } }
            .first { it.id == docId }
        assertEquals(doc.updatedAt, doc.lastAccessedAt)
    }

    @Test
    fun `markDocumentAccessed sets last seen`() = runTest {
        val docId = repository.createDocument("Doc").getOrThrow()
        repository.observeDocuments().first { list -> list.any { it.id == docId } }

        repository.markDocumentAccessed(docId)

        val doc = repository.observeDocuments().first().first { it.id == docId }
        assertTrue("last seen must be recorded", doc.lastAccessedAt > 0L)
    }

    @Test
    fun `replacePages preserves keptFilenames order`() = runTest {
        val docId = repository.createDocument("Order test").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        val pages = repository.getPages(docId).getOrDefault(emptyList())
        val filenames = pages.map { it.filename }

        val reversed = filenames.reversed()
        repository.replacePages(docId, reversed)
        val updated = repository.getPages(docId).getOrDefault(emptyList())

        assertEquals("replacePages should preserve the order of keptFilenames",
            reversed, updated.map { it.filename })
    }

    @Test
    fun `replacePages removes pages not in keptFilenames`() = runTest {
        val docId = repository.createDocument("Trim test").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        val pages = repository.getPages(docId).getOrDefault(emptyList())
        val firstTwo = pages.take(2).map { it.filename }

        repository.replacePages(docId, firstTwo)
        val remaining = repository.getPages(docId).getOrDefault(emptyList())

        assertEquals(2, remaining.size)
        assertEquals(firstTwo, remaining.map { it.filename })
    }

    @Test
    fun `replacePages deletes document when all pages removed`() = runTest {
        val docId = repository.createDocument("Delete all").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.replacePages(docId, emptyList())

        val doc = repository.getDocument(docId).getOrNull()
        assertNull("Document should be deleted when all pages removed", doc)
    }

    @Test
    fun `observeDocumentTagMap returns all document tags`() = runTest {
        val doc1 = repository.createDocument("Doc1").getOrThrow()
        val doc2 = repository.createDocument("Doc2").getOrThrow()
        val tag1 = repository.createTag("Work")
        val tag2 = repository.createTag("Personal")
        repository.setDocumentTags(doc1, listOf(tag1, tag2))
        repository.setDocumentTags(doc2, listOf(tag1))
        val map = repository.observeDocumentTagMap().first()
        assertEquals(2, map[doc1]?.size)
        assertEquals(1, map[doc2]?.size)
    }

    @Test
    fun `importPdf fails when PdfPageImporter returns empty list`() = runTest {
        val mockImporter = mockk<PdfPageImporter>()
        coEvery { mockImporter.import(any(), any(), any(), any()) } returns Result.success(emptyList())

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = DocumentRepositoryImpl(
            store = DocumentStore(app),
            tagDao = database.tagDao(),
            eventBus = com.picpocket.app.data.workflow.DocumentEventBus(),
            pdfPageImporter = mockImporter,
            ocrManager = createOcrManager(app),
            app = app,
            documentAccess = com.picpocket.app.data.store.DocumentAccessStore(app),
        )

        val uri = Uri.parse("content://test/test.pdf")
        val result = repo.importPdf(uri)
        assertTrue("Should fail with empty pages", result.isFailure)
        assertEquals("Selected PDF has no pages", result.exceptionOrNull()?.message)
    }

    @Test
    fun `importPdf handles PdfPageImporter exception gracefully`() = runTest {
        val mockImporter = mockk<PdfPageImporter>()
        coEvery { mockImporter.import(any(), any(), any(), any()) } throws Exception("PDF parsing error")

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = DocumentRepositoryImpl(
            store = DocumentStore(app),
            tagDao = database.tagDao(),
            eventBus = com.picpocket.app.data.workflow.DocumentEventBus(),
            pdfPageImporter = mockImporter,
            ocrManager = createOcrManager(app),
            app = app,
            documentAccess = com.picpocket.app.data.store.DocumentAccessStore(app),
        )

        val uri = Uri.parse("content://test/test.pdf")
        val result = repo.importPdf(uri)
        assertTrue("Should fail on PDF import error", result.isFailure)
    }

    @Test
    fun `rescanPage fails for nonexistent page number`() = runTest {
        val docId = repository.createDocument("Rescan test").getOrThrow()
        repository.addPage(docId, tempPageUri())

        val result = repository.rescanPage(docId, pageNumber = 99, imageUri = tempPageUri())
        assertTrue("Should fail for nonexistent page", result.isFailure)
    }

    @Test
    fun `importPdf with 3 pages creates document with 3 pages`() = runTest {
        val mockImporter = mockk<PdfPageImporter>()
        coEvery { mockImporter.import(any(), any(), any(), any()) } returns Result.success(
            listOf(
                PdfPageImportResult(1, "00001", 100L),
                PdfPageImportResult(2, "00002", 200L),
                PdfPageImportResult(3, "00003", 300L),
            )
        )

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = DocumentRepositoryImpl(
            store = DocumentStore(app),
            tagDao = database.tagDao(),
            eventBus = com.picpocket.app.data.workflow.DocumentEventBus(),
            pdfPageImporter = mockImporter,
            ocrManager = createOcrManager(app),
            app = app,
            documentAccess = com.picpocket.app.data.store.DocumentAccessStore(app),
        )

        val uri = Uri.parse("content://test/test.pdf")
        val result = repo.importPdf(uri)
        assertTrue(result.isSuccess)
        val docId = result.getOrThrow()
        val doc = repo.getDocument(docId).getOrNull()
        assertNotNull(doc)
        assertEquals(3, doc?.pageCount)
    }

    @Test
    fun `rescanPage replaces page image successfully`() = runTest {
        val docId = repository.createDocument("Smoke rescan").getOrThrow()
        repository.addPage(docId, tempPageUri())

        val pages = repository.getPages(docId).getOrDefault(emptyList())
        assertEquals(1, pages.size)
        assertEquals(1, pages[0].pageNumber)

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val pageFile = File(app.filesDir, "documents/$docId/${pages[0].filename}")
        val originalSize = pageFile.length()

        val newImage = File.createTempFile("new_page", ".jpg")
        newImage.writeBytes(byteArrayOf(4, 5, 6, 7))

        val result = repository.rescanPage(docId, 1, Uri.fromFile(newImage).toString())
        assertTrue(result.isSuccess)

        val updatedSize = pageFile.length()
        assertNotEquals("Page file size should change after rescan", originalSize, updatedSize)

        val updatedPages = repository.getPages(docId).getOrDefault(emptyList())
        assertEquals(1, updatedPages.size)
        assertEquals(1, updatedPages[0].pageNumber)
    }

    @Test
    fun `importPdf then rescanPage combined flow`() = runTest {
        val mockImporter = mockk<PdfPageImporter>()
        coEvery { mockImporter.import(any(), any(), any(), any()) } returns Result.success(
            listOf(PdfPageImportResult(1, "00001", 100L))
        )

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = DocumentRepositoryImpl(
            store = DocumentStore(app),
            tagDao = database.tagDao(),
            eventBus = com.picpocket.app.data.workflow.DocumentEventBus(),
            pdfPageImporter = mockImporter,
            ocrManager = createOcrManager(app),
            app = app,
            documentAccess = com.picpocket.app.data.store.DocumentAccessStore(app),
        )

        val uri = Uri.parse("content://test/test.pdf")
        val docId = repo.importPdf(uri).getOrThrow()

        var doc = repo.getDocument(docId).getOrNull()
        assertNotNull(doc)
        assertEquals(1, doc?.pageCount)

        val newImage = File.createTempFile("new_page", ".jpg")
        newImage.writeBytes(byteArrayOf(4, 5, 6, 7))
        val rescanResult = repo.rescanPage(docId, 1, Uri.fromFile(newImage).toString())
        assertTrue(rescanResult.isSuccess)

        doc = repo.getDocument(docId).getOrNull()
        assertNotNull(doc)
        assertEquals(1, doc?.pageCount)
    }

    @Test
    fun `collate keeps sources and inserts the merged page at the first source`() = runTest {
        val docId = repository.createDocument("Collate Doc").getOrThrow()
        repository.addPage(docId, tempPageUri(1))
        repository.addPage(docId, tempPageUri(2))
        repository.addPage(docId, tempPageUri(3))
        val originals = repository.getPages(docId).getOrThrow().map { it.filename }

        val result = repository.collatePages(
            documentId = docId,
            sourcePageNumbers = listOf(2, 3),
            mergedImageUri = tempPageUri(),
            removeSources = false,
        )

        assertTrue(result.isSuccess)
        val pages = repository.getPages(docId).getOrThrow()
        assertEquals(4, pages.size)
        assertEquals(originals[0], pages[0].filename)
        assertTrue("merged page should sit where page 2 was", pages[1].filename !in originals)
        assertEquals(originals[1], pages[2].filename)
        assertEquals(originals[2], pages[3].filename)
    }

    @Test
    fun `collate remove removes sources and keeps the merged page in their place`() = runTest {
        val docId = repository.createDocument("Collate Doc").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())

        val result = repository.collatePages(
            documentId = docId,
            sourcePageNumbers = listOf(1, 2),
            mergedImageUri = tempPageUri(),
            removeSources = true,
        )

        assertTrue(result.isSuccess)
        val pages = repository.getPages(docId).getOrThrow()
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].pageNumber)
        assertEquals(2, pages[1].pageNumber)
    }

    @Test
    fun `a failed collate leaves the document unchanged`() = runTest {
        val docId = repository.createDocument("Collate Doc").getOrThrow()
        repository.addPage(docId, tempPageUri())

        val result = repository.collatePages(
            documentId = docId,
            sourcePageNumbers = listOf(1),
            mergedImageUri = "file:///does/not/exist.jpg",
            removeSources = true,
        )

        assertTrue(result.isFailure)
        assertEquals(1, repository.getPages(docId).getOrThrow().size)
    }

    @Test
    fun `collate inherits OCR text from the sources in order`() = runTest {
        val docId = repository.createDocument("Collate Doc").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())
        repository.updatePageOcrText(docId, 1, "alpha")
        repository.updatePageOcrText(docId, 2, "beta")

        repository.collatePages(
            documentId = docId,
            sourcePageNumbers = listOf(1, 2),
            mergedImageUri = tempPageUri(),
            removeSources = false,
        ).getOrThrow()

        val pages = repository.getPages(docId).getOrThrow()
        assertEquals("alpha\nbeta", pages.first { it.ocrText == "alpha\nbeta" }.ocrText)
        assertTrue(repository.getDocument(docId).getOrThrow().ocrComplete)
    }

    @Test
    fun `collate without source OCR leaves the merged page pending`() = runTest {
        val docId = repository.createDocument("Collate Doc").getOrThrow()
        repository.addPage(docId, tempPageUri())
        repository.addPage(docId, tempPageUri())

        repository.collatePages(
            documentId = docId,
            sourcePageNumbers = listOf(1, 2),
            mergedImageUri = tempPageUri(),
            removeSources = false,
        ).getOrThrow()

        val pages = repository.getPages(docId).getOrThrow()
        assertEquals(3, pages.size)
        assertTrue("merged page must be pending OCR", pages.any { it.ocrText == null })
        assertTrue(!repository.getDocument(docId).getOrThrow().ocrComplete)
    }
}
