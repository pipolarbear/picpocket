package com.picpocket.app.drive.sync

import com.picpocket.app.data.model.PageKind
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.MetadataNaming
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.data.store.StoredPage
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class UploadEngineTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveFileManager = mockk<DriveFileManager>()
    private val documentStore = mockk<DocumentStore>()
    private val localDriveIndex = mockk<LocalDriveIndex>()

    private lateinit var uploadEngine: UploadEngine

    @Before
    fun setUp() {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.passphraseCount } returns 1
        uploadEngine = UploadEngine(driveFileManager, documentStore, localDriveIndex)
    }

    private fun doc(id: String, pages: List<StoredPage>): StoredDocument =
        StoredDocument(id = id, name = "Doc", createdAt = 0L, updatedAt = 0L, pages = pages.toMutableList())

    private fun tempPage(): File {
        val f = File.createTempFile("test", ".jpg")
        f.writeBytes(byteArrayOf(1, 2, 3))
        return f
    }

    @Test
    fun `uploadNewDocument returns false when page write fails`() = runTest {
        val d = doc("doc-1", listOf(StoredPage(pageNumber = 1, filename = "abc123.jpg", createdAt = 0L)))
        coEvery { documentStore.readMetadata("doc-1") } returns Result.success(d)
        coEvery { documentStore.metadataVersion("doc-1") } returns 0
        coEvery { driveFileManager.createDocFolder("content://tree/", "doc-1") } returns true
        every { documentStore.pageFile("doc-1", "abc123.jpg") } returns tempPage()
        coEvery { driveFileManager.writeFile(any(), any(), any(), any()) } returns WriteOutcome.Failed("boom")

        val result = uploadEngine.uploadNewDocument("doc-1")

        assertFalse(result)
    }

    @Test
    fun `uploadNewDocument returns false when metadata write fails`() = runTest {
        val d = doc("doc-1", emptyList())
        coEvery { documentStore.readMetadata("doc-1") } returns Result.success(d)
        coEvery { documentStore.metadataVersion("doc-1") } returns 0
        coEvery { driveFileManager.createDocFolder(any(), any()) } returns true
        coEvery { driveFileManager.writeFile(any(), any(), eq(MetadataNaming.name(0, 1)), any()) } returns WriteOutcome.Failed("meta boom")

        val result = uploadEngine.uploadNewDocument("doc-1")

        assertFalse(result)
    }

    @Test
    fun `uploadNewDocument verifies pages, writes renamed metadata and records local version`() = runTest {
        val d = doc("doc-1", listOf(StoredPage(pageNumber = 1, filename = "abc123.jpg", createdAt = 0L)))
        coEvery { documentStore.readMetadata("doc-1") } returns Result.success(d)
        coEvery { documentStore.metadataVersion("doc-1") } returns 0
        coEvery { driveFileManager.createDocFolder(any(), any()) } returns true
        every { documentStore.pageFile("doc-1", "abc123.jpg") } returns tempPage()
        coEvery { driveFileManager.writeFile(any(), any(), any(), any()) } returns WriteOutcome.Verified
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1") } returns emptyList()
        coEvery { documentStore.writeMetadataAt("doc-1", d, 0, 1) } returns Result.success(Unit)

        val result = uploadEngine.uploadNewDocument("doc-1")

        assertTrue(result)
        coVerify { driveFileManager.writeFile(any(), any(), eq(MetadataNaming.name(0, 1)), any()) }
        coVerify { documentStore.writeMetadataAt("doc-1", d, 0, 1) }
    }

    @Test
    fun `pushDocument uploads missing pages and deletes unreferenced remote pages`() = runTest {
        val d = doc("doc-1", listOf(StoredPage(pageNumber = 1, filename = "abc123.jpg", createdAt = 0L)))
        coEvery { documentStore.readMetadata("doc-1") } returns Result.success(d)
        coEvery { documentStore.metadataVersion("doc-1") } returns 4
        coEvery { driveFileManager.createDocFolder(any(), any()) } returns true
        every { documentStore.pageFile("doc-1", "abc123.jpg") } returns tempPage()
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1") } returns listOf("stale.jpg", MetadataNaming.name(3, 1))
        coEvery { driveFileManager.writeFile(any(), any(), any(), any()) } returns WriteOutcome.Verified
        coEvery { driveFileManager.deleteFileByName(any(), any(), any()) } returns true
        coEvery { documentStore.writeMetadataAt("doc-1", d, 4, 1) } returns Result.success(Unit)

        val result = uploadEngine.pushDocument("doc-1", 1)

        assertTrue(result)
        coVerify { driveFileManager.writeFile(any(), any(), eq("abc123.jpg"), any()) }
        coVerify { driveFileManager.deleteFileByName("content://tree/", "doc-1", "stale.jpg") }
        coVerify { driveFileManager.deleteFileByName("content://tree/", "doc-1", MetadataNaming.name(3, 1)) }
        coVerify { documentStore.writeMetadataAt("doc-1", d, 4, 1) }
    }

    @Test
    fun `forceReEncryptDocument re-uploads every page under current count`() = runTest {        val d = doc("doc-1", listOf(StoredPage(pageNumber = 1, filename = "abc123.jpg", createdAt = 0L)))
        coEvery { documentStore.readMetadata("doc-1") } returns Result.success(d)
        coEvery { documentStore.metadataVersion("doc-1") } returns 2
        coEvery { driveFileManager.createDocFolder(any(), any()) } returns true
        every { documentStore.pageFile("doc-1", "abc123.jpg") } returns tempPage()
        coEvery { driveFileManager.writeFile(any(), any(), any(), any()) } returns WriteOutcome.Verified
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1") } returns listOf("abc123.jpg", MetadataNaming.name(2, 0))
        coEvery { driveFileManager.deleteFileByName(any(), any(), any()) } returns true
        coEvery { documentStore.writeMetadataAt("doc-1", d, 2, 1) } returns Result.success(Unit)

        val result = uploadEngine.forceReEncryptDocument("doc-1")

        assertTrue(result)
        coVerify { driveFileManager.writeFile(any(), any(), eq("abc123.jpg"), any()) }
        coVerify { driveFileManager.deleteFileByName("content://tree/", "doc-1", MetadataNaming.name(2, 0)) }
        coVerify { documentStore.writeMetadataAt("doc-1", d, 2, 1) }
    }

    @Test
    fun `uploadNewDocument uploads a shared native pdf source`() = runTest {
        val d = doc(
            "doc-1",
            listOf(
                StoredPage(pageNumber = 1, filename = "source.pdf", createdAt = 0L, kind = PageKind.PDF, pdfPageIndex = 0),
                StoredPage(pageNumber = 2, filename = "source.pdf", createdAt = 0L, kind = PageKind.PDF, pdfPageIndex = 1),
            ),
        )
        coEvery { documentStore.readMetadata("doc-1") } returns Result.success(d)
        coEvery { documentStore.metadataVersion("doc-1") } returns 0
        coEvery { driveFileManager.createDocFolder(any(), any()) } returns true
        every { documentStore.pageFile("doc-1", "source.pdf") } returns tempPage()
        coEvery { driveFileManager.writeFile(any(), any(), any(), any()) } returns WriteOutcome.Verified
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1") } returns emptyList()
        coEvery { documentStore.writeMetadataAt("doc-1", d, 0, 1) } returns Result.success(Unit)

        val result = uploadEngine.uploadNewDocument("doc-1")

        assertTrue(result)
        coVerify { driveFileManager.writeFile(any(), any(), eq("source.pdf"), any()) }
    }
}
