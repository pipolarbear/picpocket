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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

private fun createTempDir(): File = Files.createTempDirectory("download-test").toFile()

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DownloadEngineTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveFileManager = mockk<DriveFileManager>()
    private val documentStore = mockk<DocumentStore>()
    private val localDriveIndex = mockk<LocalDriveIndex>()

    private lateinit var engine: DownloadEngine

    @Before
    fun setUp() {
        engine = DownloadEngine(driveFileManager, documentStore, localDriveIndex)
    }

    @Test
    fun `listRemoteDocuments derives version and passphrase from metadata filename`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        coEvery { driveFileManager.listDocFolders("content://tree/") } returns listOf("doc-1")
        val meta = StoredDocument(id = "doc-1", name = "Doc", createdAt = 0L, updatedAt = 0L)
        val metaName = MetadataNaming.name(4, 2)
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1", any()) } returns
            listOf("abc123.jpg", metaName)
        coEvery { driveFileManager.readMetadataJson("content://tree/", "doc-1", metaName, any()) } returns
            kotlinx.serialization.json.Json.encodeToString(
                com.picpocket.app.data.store.StoredDocument.serializer(), meta
            ).toByteArray(Charsets.UTF_8)

        val docs = engine.listRemoteDocuments(emptyMap())

        assertEquals(1, docs.size)
        val remote = docs.first()
        assertEquals(4, remote.version)
        assertEquals(2, remote.passphrase)
        assertEquals(listOf("abc123.jpg"), remote.fileNames)
        assertNotNull(remote.metadata)
    }

    @Test
    fun `listRemoteDocuments marks deleted docs and excludes metadata names from fileNames`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        coEvery { driveFileManager.listDocFolders("content://tree/") } returns listOf("doc-1")
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1", any()) } returns
            listOf(".deleted", MetadataNaming.name(1, 0), "abc123.jpg")

        val docs = engine.listRemoteDocuments(emptyMap())

        assertEquals(1, docs.size)
        assertTrue(docs.first().isDeleted)
        assertEquals(listOf("abc123.jpg"), docs.first().fileNames)
        assertNull(docs.first().metadata)
    }

    @Test
    fun `listRemoteDocuments falls back to version zero when no metadata name present`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        coEvery { driveFileManager.listDocFolders("content://tree/") } returns listOf("doc-1")
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1", any()) } returns
            listOf("abc123.jpg")

        val docs = engine.listRemoteDocuments(emptyMap())

        assertEquals(1, docs.size)
        assertEquals(0, docs.first().version)
        assertEquals(0, docs.first().passphrase)
    }

    @Test
    fun `pullDocument downloads files and writes metadata at remote version`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        val meta = StoredDocument(id = "doc-1", name = "Doc", createdAt = 0L, updatedAt = 0L)
        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1",
            fileNames = listOf("abc123.jpg"),
            metadata = meta,
            version = 4,
            passphrase = 2,
            isDeleted = false,
        )
        val pageFile = File.createTempFile("page", ".jpg")
        coEvery { driveFileManager.readFile("content://tree/", "doc-1", "abc123.jpg", any()) } returns
            byteArrayOf(9, 8, 7)
        every { documentStore.pageFile("doc-1", "abc123.jpg") } returns pageFile
        every { documentStore.documentDir("doc-1") } returns createTempDir()
        coEvery { documentStore.writeMetadataAt("doc-1", meta, 4, 2) } returns Result.success(Unit)

        val result = engine.pullDocument(remote, emptyMap())

        assertTrue(result)
        assertTrue(pageFile.readBytes().contentEquals(byteArrayOf(9, 8, 7)))
        coVerify { documentStore.writeMetadataAt("doc-1", meta, 4, 2) }
    }

    @Test
    fun `pullDocument downloads a native pdf source and writes metadata`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        val meta = StoredDocument(
            id = "doc-1",
            name = "Doc",
            createdAt = 0L,
            updatedAt = 0L,
            pages = mutableListOf(
                StoredPage(pageNumber = 1, filename = "source.pdf", createdAt = 0L, kind = PageKind.PDF, pdfPageIndex = 0),
            ),
        )
        val remote = DownloadEngine.RemoteDocument("doc-1", listOf("source.pdf"), meta, 4, 2, false)
        val pageFile = File.createTempFile("source", ".pdf")
        coEvery { driveFileManager.readFile("content://tree/", "doc-1", "source.pdf", any()) } returns byteArrayOf(1, 2, 3)
        every { documentStore.pageFile("doc-1", "source.pdf") } returns pageFile
        every { documentStore.documentDir("doc-1") } returns createTempDir()
        coEvery { documentStore.writeMetadataAt("doc-1", meta, 4, 2) } returns Result.success(Unit)

        val result = engine.pullDocument(remote, emptyMap())

        assertTrue(result)
        assertTrue(pageFile.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        coVerify { documentStore.writeMetadataAt("doc-1", meta, 4, 2) }
    }
}
