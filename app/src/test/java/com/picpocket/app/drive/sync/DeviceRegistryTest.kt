package com.picpocket.app.drive.sync

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.MetadataNaming
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DeviceRegistryTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveFileManager = mockk<DriveFileManager>()
    private val documentStore = mockk<DocumentStore>()
    private val localDriveIndex = mockk<LocalDriveIndex>()
    private val context = mockk<Context>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var registry: DeviceRegistry

    private val localDoc = StoredDocument(
        id = "doc-1",
        name = "Test Doc",
        createdAt = 100L,
        updatedAt = 200L,
    )

    @Before
    fun setUp() {
        registry = DeviceRegistry(driveFileManager, documentStore, localDriveIndex, context)
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentFile::class)
    }

    @Test
    fun `detectOrphans identifies own deletion`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { localDriveIndex.getDevices() } returns mapOf("device-1" to DeviceInfo("My Phone", 100L, 200L))

        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1",
            fileNames = listOf(".deleted"),
            metadata = null,
            version = 0,
            passphrase = 0,
            isDeleted = true,
        )
        val tombstone = TombstoneData(deletedAt = 300L, byDevice = "device-1", acknowledgedBy = listOf("device-1"))
        coEvery { driveFileManager.readFile("content://tree/", "doc-1", ".deleted") } returns
            json.encodeToString(tombstone).toByteArray(Charsets.UTF_8)

        registry.detectOrphans(listOf(localDoc), listOf(remote))

        val myDeleted = registry.getMyDeleted()
        assertEquals(1, myDeleted.size)
        assertEquals("doc-1", myDeleted[0].docId)
        assertTrue(myDeleted[0].isOwnDeletion)
        assertEquals("My Phone", myDeleted[0].deletingDeviceName)
        assertEquals(300L, myDeleted[0].deletedAt)
    }

    @Test
    fun `detectOrphans identifies others deletion`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "device-2"
        every { localDriveIndex.getDevices() } returns mapOf(
            "device-1" to DeviceInfo("Other Phone", 100L, 200L),
            "device-2" to DeviceInfo("My Phone", 150L, 250L),
        )

        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1",
            fileNames = listOf(".deleted"),
            metadata = null,
            version = 0,
            passphrase = 0,
            isDeleted = true,
        )
        val tombstone = TombstoneData(deletedAt = 300L, byDevice = "device-1", acknowledgedBy = listOf("device-1"))
        coEvery { driveFileManager.readFile("content://tree/", "doc-1", ".deleted") } returns
            json.encodeToString(tombstone).toByteArray(Charsets.UTF_8)

        registry.detectOrphans(listOf(localDoc), listOf(remote))

        val othersDeleted = registry.getOthersDeleted()
        assertEquals(1, othersDeleted.size)
        assertEquals("doc-1", othersDeleted[0].docId)
        assertFalse(othersDeleted[0].isOwnDeletion)
        assertEquals("Other Phone", othersDeleted[0].deletingDeviceName)
    }

    @Test
    fun `detectOrphans uses deviceId as fallback name when device not in registry`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "device-2"
        every { localDriveIndex.getDevices() } returns mapOf("device-2" to DeviceInfo("My Phone", 150L, 250L))

        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1",
            fileNames = listOf(".deleted"),
            metadata = null,
            version = 0,
            passphrase = 0,
            isDeleted = true,
        )
        val tombstone = TombstoneData(deletedAt = 300L, byDevice = "unknown-device", acknowledgedBy = listOf("unknown-device"))
        coEvery { driveFileManager.readFile("content://tree/", "doc-1", ".deleted") } returns
            json.encodeToString(tombstone).toByteArray(Charsets.UTF_8)

        registry.detectOrphans(listOf(localDoc), listOf(remote))

        val othersDeleted = registry.getOthersDeleted()
        assertEquals(1, othersDeleted.size)
        assertEquals("unknown-device", othersDeleted[0].deletingDeviceName)
    }

    @Test
    fun `detectOrphans skips non-deleted remote docs`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { localDriveIndex.getDevices() } returns emptyMap()

        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1",
            fileNames = listOf("page_001.jpg"),
            metadata = null,
            version = 0,
            passphrase = 0,
            isDeleted = false,
        )

        registry.detectOrphans(listOf(localDoc), listOf(remote))
        assertTrue(registry.getOrphans().isEmpty())
    }

    @Test
    fun `detectOrphans skips remote deleted docs not present locally`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { localDriveIndex.getDevices() } returns emptyMap()

        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-999",
            fileNames = listOf(".deleted"),
            metadata = null,
            version = 0,
            passphrase = 0,
            isDeleted = true,
        )

        registry.detectOrphans(listOf(localDoc), listOf(remote))
        assertTrue(registry.getOrphans().isEmpty())
    }

    @Test
    fun `detectOrphans does nothing when treeUri is blank`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns ""

        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1",
            fileNames = listOf(".deleted"),
            metadata = null,
            version = 0,
            passphrase = 0,
            isDeleted = true,
        )

        registry.detectOrphans(listOf(localDoc), listOf(remote))
        assertTrue(registry.getOrphans().isEmpty())
    }

    @Test
    fun `keepOrphan deletes tombstone and bumps local version above remote`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { localDriveIndex.getDevices() } returns mapOf("device-1" to DeviceInfo("My Phone", 100L, 200L))
        coEvery { documentStore.readMetadata("doc-1") } returns Result.success(localDoc)
        coEvery { documentStore.metadataVersion("doc-1") } returns 5
        coEvery { documentStore.metadataPassphrase("doc-1") } returns 0
        coEvery { driveFileManager.listFileNames("content://tree/", "doc-1") } returns
            listOf(MetadataNaming.name(3, 0), "abc123.jpg")
        coEvery { documentStore.writeMetadataAt("doc-1", localDoc, 6, 0) } returns Result.success(Unit)
        coEvery { driveFileManager.readFile("content://tree/", "doc-1", ".deleted") } returns
            json.encodeToString(TombstoneData(300L, "device-1", listOf("device-1"))).toByteArray(Charsets.UTF_8)
        coEvery { driveFileManager.deleteFileByName("content://tree/", "doc-1", ".deleted") } returns true

        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1", fileNames = listOf(".deleted"), metadata = null,
            version = 0, passphrase = 0, isDeleted = true,
        )
        registry.detectOrphans(listOf(localDoc), listOf(remote))
        assertEquals(1, registry.getOrphans().size)

        registry.keepOrphan("doc-1")

        coVerify { driveFileManager.deleteFileByName("content://tree/", "doc-1", ".deleted") }
        coVerify { documentStore.writeMetadataAt("doc-1", localDoc, 6, 0) }
        assertTrue(registry.getOrphans().isEmpty())
    }

    @Test
    fun `deleteOrphanLocally deletes doc and acknowledges tombstone`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { localDriveIndex.getDevices() } returns mapOf("device-1" to DeviceInfo("My Phone", 100L, 200L))
        coEvery { documentStore.deleteDocument("doc-1") } returns Result.success(Unit)
        coEvery { driveFileManager.readFile("content://tree/", "doc-1", ".deleted") } returns
            json.encodeToString(TombstoneData(300L, "device-2", listOf("device-2"))).toByteArray(Charsets.UTF_8)
        coEvery { driveFileManager.writeFile(any(), any(), any(), any(), any()) } returns WriteOutcome.Verified

        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1", fileNames = listOf(".deleted"), metadata = null,
            version = 0, passphrase = 0, isDeleted = true,
        )
        registry.detectOrphans(listOf(localDoc), listOf(remote))
        assertEquals(1, registry.getOrphans().size)

        registry.deleteOrphanLocally("doc-1")

        coVerify { documentStore.deleteDocument("doc-1") }
        coVerify { driveFileManager.writeFile("content://tree/", "doc-1", ".deleted", any(), any()) }
        assertTrue(registry.getOrphans().isEmpty())
    }

    @Test
    fun `dismissOrphan acknowledges tombstone without deleting locally`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "device-2"
        every { localDriveIndex.getDevices() } returns mapOf(
            "device-1" to DeviceInfo("Other Phone", 100L, 200L),
            "device-2" to DeviceInfo("My Phone", 150L, 250L),
        )
        coEvery { driveFileManager.readFile("content://tree/", "doc-1", ".deleted") } returns
            json.encodeToString(TombstoneData(300L, "device-1", listOf("device-1"))).toByteArray(Charsets.UTF_8)
        coEvery { driveFileManager.writeFile(any(), any(), any(), any(), any()) } returns WriteOutcome.Verified

        val remote = DownloadEngine.RemoteDocument(
            docId = "doc-1", fileNames = listOf(".deleted"), metadata = null,
            version = 0, passphrase = 0, isDeleted = true,
        )
        registry.detectOrphans(listOf(localDoc), listOf(remote))
        assertEquals(1, registry.getOrphans().size)

        registry.dismissOrphan("doc-1")

        coVerify { driveFileManager.writeFile("content://tree/", "doc-1", ".deleted", any(), any()) }
        assertTrue(registry.getOrphans().isEmpty())
    }

    @Test
    fun `syncRegistryToDrive does nothing when treeUri is blank`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns ""
        registry.syncRegistryToDrive()
    }

    @Test
    fun `syncRegistryToDrive does nothing when deviceId is blank`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns ""
        registry.syncRegistryToDrive()
    }

    @Test
    fun `syncRegistryFromDrive does nothing when treeUri is blank`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns ""
        registry.syncRegistryFromDrive()
    }

    @Test
    fun `syncRegistryToDrive returns after verified write`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { localDriveIndex.getDevices() } returns mapOf("device-1" to DeviceInfo("My Phone", 100L, 200L))
        every { localDriveIndex.setDevice(any(), any()) } returns Unit
        coEvery { driveFileManager.writeRootFile("content://tree/", "devices.json", any()) } returns WriteOutcome.Verified

        registry.syncRegistryToDrive(encrypted = true)

        coVerify(exactly = 1) { driveFileManager.writeRootFile("content://tree/", "devices.json", any()) }
    }

    @Test
    fun `syncRegistryToDrive retries then throws RegistryWriteException with reason`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { localDriveIndex.getDevices() } returns mapOf("device-1" to DeviceInfo("My Phone", 100L, 200L))
        every { localDriveIndex.setDevice(any(), any()) } returns Unit
        coEvery { driveFileManager.writeRootFile("content://tree/", "devices.json", any()) } returns
            WriteOutcome.Failed("write not settled")

        val ex = runCatching { registry.syncRegistryToDrive(encrypted = true) }.exceptionOrNull()

        assertTrue(ex is RegistryWriteException)
        assertTrue(ex!!.message!!.contains("write not settled"))
        coVerify(exactly = 3) { driveFileManager.writeRootFile("content://tree/", "devices.json", any()) }
    }

    private fun stubFromDriveRoot() {
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver

        val root = mockk<DocumentFile>()
        every { root.uri } returns Uri.parse("content://tree/")
        every { root.listFiles() } returns arrayOf()

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, Uri.parse("content://tree/")) } returns root
    }

    @Test
    fun `syncRegistryFromDrive throws CorruptRegistryException when registry is undecodable`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"

        val contentResolver = mockk<ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver

        val registryFile = mockk<DocumentFile>()
        every { registryFile.name } returns "devices.json"
        every { registryFile.uri } returns Uri.parse("content://tree/devices.json")
        // A fresh stream per read: `returns` would hand back the same exhausted
        // stream, so attempts 2..N would read 0 bytes and take the empty path.
        every { contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream("{ not valid json".toByteArray(Charsets.UTF_8))
        }

        val root = mockk<DocumentFile>()
        every { root.uri } returns Uri.parse("content://tree/")
        every { root.listFiles() } returns arrayOf(registryFile)

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, Uri.parse("content://tree/")) } returns root

        val ex = runCatching { registry.syncRegistryFromDrive() }.exceptionOrNull()

        assertTrue("expected CorruptRegistryException but was ${ex?.javaClass?.simpleName}", ex is CorruptRegistryException)
    }

    @Test
    fun `syncRegistryFromDrive recovers after transient empty read`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"
        every { localDriveIndex.getLocalDeviceId() } returns "local-device"
        every { localDriveIndex.getDevices() } returns emptyMap()
        every { localDriveIndex.setDevice(any(), any()) } returns Unit

        val contentResolver = mockk<ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver

        val registryJson = json.encodeToString(
            SharedDeviceRegistry(
                devices = listOf(SharedDevice(id = "dev1", name = "A", lastSeen = 123L)),
                encrypted = true,
            ),
        )
        val registryFile = mockk<DocumentFile>()
        every { registryFile.name } returns "devices.json"
        every { registryFile.uri } returns Uri.parse("content://tree/devices.json")
        every { contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(ByteArray(0)),
            ByteArrayInputStream(registryJson.toByteArray(Charsets.UTF_8)),
        )

        val root = mockk<DocumentFile>()
        every { root.uri } returns Uri.parse("content://tree/")
        every { root.listFiles() } returns arrayOf(registryFile)

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, Uri.parse("content://tree/")) } returns root

        registry.syncRegistryFromDrive()

        assertTrue("expected remoteEncrypted to be set after retry", registry.remoteEncrypted)
    }

    @Test
    fun `syncRegistryFromDrive returns when registry file is absent`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/"

        stubFromDriveRoot()

        registry.syncRegistryFromDrive()

        assertFalse(registry.remoteEncrypted)
    }

    @Test
    fun `getDeviceName returns non-empty string`() {
        val name = registry.getDeviceName()
        assertTrue(name.isNotBlank())
    }

    @Test
    fun `initially no orphans`() {
        assertTrue(registry.getOrphans().isEmpty())
        assertTrue(registry.getMyDeleted().isEmpty())
        assertTrue(registry.getOthersDeleted().isEmpty())
    }
}
