package com.picpocket.app.ui

import android.app.Application
import android.net.Uri
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.ui.components.MatchMode
import com.picpocket.app.ui.screens.home.HomeViewModel
import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class HomeViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repo: FakeDocumentRepository
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        repo = FakeDocumentRepository()
        val app = RuntimeEnvironment.getApplication() as Application
        viewModel = HomeViewModel(app, repo, FakePdfGenerator())
    }

    @Test
    fun `initial state has loading true`() {
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `loads documents from repository`() = runTest {
        repo.createDocument("Doc1")
        repo.createDocument("Doc2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.documents.size)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `sort by name orders alphabetically`() = runTest {
        repo.createDocument("Beta")
        repo.createDocument("Alpha")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSortOrder(com.picpocket.app.ui.screens.home.SortOrder.NAME_ASC)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Alpha", viewModel.uiState.value.documents[0].name)
        assertEquals("Beta", viewModel.uiState.value.documents[1].name)
    }

    @Test
    fun `sort by modified orders descending`() = runTest {
        val id1 = repo.createDocument("Doc1").getOrThrow()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        Thread.sleep(10)
        val id2 = repo.createDocument("Doc2").getOrThrow()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(id2, viewModel.uiState.value.documents[0].id)
        assertEquals(id1, viewModel.uiState.value.documents[1].id)
    }

    @Test
    fun `toggle selection mode`() = runTest {
        val docId = repo.createDocument("Doc1").getOrThrow()
        assertFalse(viewModel.uiState.value.selectionMode)
        viewModel.onDocumentLongPress(docId)
        assertTrue(viewModel.uiState.value.selectionMode)
        assertEquals(setOf(docId), viewModel.uiState.value.selectedDocumentIds)
    }

    @Test
    fun `tap in selection mode toggles without navigating`() = runTest {
        repo.createDocument("Doc1")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val docId = viewModel.uiState.value.documents[0].id
        viewModel.onDocumentLongPress(docId)
        assertTrue(viewModel.uiState.value.selectionMode)

        val shouldNavigate = viewModel.onDocumentTap(docId)
        assertFalse(shouldNavigate)
        assertTrue(viewModel.uiState.value.selectedDocumentIds.isEmpty())
    }

    @Test
    fun `select all selects all documents`() = runTest {
        repo.createDocument("Doc1")
        repo.createDocument("Doc2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(viewModel.uiState.value.documents[0].id)
        viewModel.selectAll()
        assertEquals(2, viewModel.uiState.value.selectedDocumentIds.size)
    }

    @Test
    fun `toggleSelectAll selects all then deselects all`() = runTest {
        repo.createDocument("Doc1")
        repo.createDocument("Doc2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(viewModel.uiState.value.documents[0].id)
        viewModel.toggleSelectAll()
        assertEquals(2, viewModel.uiState.value.selectedDocumentIds.size)
        viewModel.toggleSelectAll()
        assertTrue(viewModel.uiState.value.selectedDocumentIds.isEmpty())
    }

    @Test
    fun `exit selection mode`() = runTest {
        repo.createDocument("Doc1")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(viewModel.uiState.value.documents[0].id)
        viewModel.exitSelectionMode()
        assertFalse(viewModel.uiState.value.selectionMode)
    }

    @Test
    fun `delete selected documents`() = runTest {
        repo.createDocument("Doc1")
        repo.createDocument("Doc2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.documents.size)

        viewModel.onDocumentLongPress(viewModel.uiState.value.documents[0].id)
        viewModel.selectAll()
        viewModel.showDeleteConfirmation()
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)

        viewModel.deleteSelected()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `search query filters by name`() = runTest {
        repo.createDocument("Tax Return 2024")
        repo.createDocument("Medical Report")
        repo.createDocument("Tax Notes")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("Tax")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.documents.size)
        assertTrue(viewModel.uiState.value.documents.all { it.name.startsWith("Tax") })
    }

    @Test
    fun `search query is case insensitive`() = runTest {
        repo.createDocument("Invoice")
        repo.createDocument("Receipt")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("INVOICE")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.documents.size)
        assertEquals("Invoice", viewModel.uiState.value.documents[0].name)
    }

    @Test
    fun `search with regex pattern`() = runTest {
        repo.createDocument("Report Q1 2024")
        repo.createDocument("Report Q2 2024")
        repo.createDocument("Notes")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("Report Q[12]")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `clear search shows all documents`() = runTest {
        repo.createDocument("Alpha")
        repo.createDocument("Beta")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("Alpha")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.documents.size)

        viewModel.setSearchQuery("")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `search in content finds documents by OCR text`() = runTest {
        val doc1 = repo.createDocument("Invoice").getOrThrow()
        val doc2 = repo.createDocument("Receipt").getOrThrow()
        repo.addPage(doc1, "content://page1.jpg")
        repo.addPage(doc2, "content://page2.jpg")
        repo.updatePageOcrText(doc1, 1, "This invoice is for $500")
        repo.updatePageOcrText(doc2, 1, "Receipt for groceries")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleSearchInContent()
        viewModel.setSearchQuery("invoice")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.documents.size)
        assertEquals("Invoice", viewModel.uiState.value.documents[0].name)
    }

    @Test
    fun `search in content matches both name and OCR`() = runTest {
        val doc1 = repo.createDocument("Tax Return").getOrThrow()
        val doc2 = repo.createDocument("Notes").getOrThrow()
        repo.addPage(doc1, "content://page1.jpg")
        repo.addPage(doc2, "content://page2.jpg")
        repo.updatePageOcrText(doc2, 1, "This is about tax deductions")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleSearchInContent()
        viewModel.setSearchQuery("tax")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `search in content only matches when toggled on`() = runTest {
        val doc1 = repo.createDocument("Invoice Summary").getOrThrow()
        repo.addPage(doc1, "content://page1.jpg")
        repo.updatePageOcrText(doc1, 1, "Total due: $1000")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("due")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `search query in ui state tracks current query`() = runTest {
        viewModel.setSearchQuery("test query")
        assertEquals("test query", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun `showTagsSheet sets state`() {
        viewModel.showTagsSheet()
        assertTrue(viewModel.uiState.value.showTagsSheet)
    }

    @Test
    fun `toggleTag adds and removes from selectedTagIds`() {
        viewModel.toggleTag(1L)
        assertTrue(1L in viewModel.uiState.value.selectedTagIds)
        viewModel.toggleTag(1L)
        assertFalse(1L in viewModel.uiState.value.selectedTagIds)
    }

    @Test
    fun `createTagAndSelect creates tag and adds to selected`() = runTest {
        viewModel.createTagAndSelect("Work")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        val tags = repo.observeAllTags().first()
        assertEquals(1, tags.size)
        assertEquals("Work", tags[0].name)
        val selected = viewModel.uiState.value.selectedTagIds
        assertEquals(tags[0].id, selected.firstOrNull())
    }

    @Test
    fun `applyTagsToSelected sets tags on selected documents`() = runTest {
        val docId = repo.createDocument("Doc1").getOrThrow()
        repo.createDocument("Doc2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        val tagId = repo.createTag("Important")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.onDocumentLongPress(docId)
        viewModel.showTagsSheet()
        viewModel.toggleTag(tagId)
        viewModel.applyTagsToSelected()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val docTags = repo.observeDocumentTags(docId).first()
        assertEquals(1, docTags.size)
        assertEquals("Important", docTags[0].name)
        assertFalse(viewModel.uiState.value.showTagsSheet)
    }

    @Test
    fun `showTagFilterSheet toggles state`() {
        assertFalse(viewModel.uiState.value.showTagFilterSheet)
        viewModel.showTagFilterSheet()
        assertTrue(viewModel.uiState.value.showTagFilterSheet)
        viewModel.hideTagFilterSheet()
        assertFalse(viewModel.uiState.value.showTagFilterSheet)
    }

    @Test
    fun `toggleFilterTag adds and removes tag from filter`() {
        viewModel.toggleFilterTag(1L)
        assertTrue(1L in viewModel.uiState.value.filterTagIds)
        viewModel.toggleFilterTag(1L)
        assertFalse(1L in viewModel.uiState.value.filterTagIds)
    }

    @Test
    fun `filter by tag limits visible documents`() = runTest {
        val doc1 = repo.createDocument("Work Doc").getOrThrow()
        val doc2 = repo.createDocument("Personal Doc").getOrThrow()
        val tagId = repo.createTag("Work")
        repo.setDocumentTags(doc1, listOf(tagId))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleFilterTag(tagId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.documents.size)
        assertEquals("Work Doc", viewModel.uiState.value.documents[0].name)
    }

    @Test
    fun `setFilterMatchMode updates mode`() {
        assertEquals(MatchMode.MATCH_ANY, viewModel.uiState.value.filterMatchMode)
        viewModel.setFilterMatchMode(MatchMode.MATCH_ALL)
        assertEquals(MatchMode.MATCH_ALL, viewModel.uiState.value.filterMatchMode)
    }

    @Test
    fun `filterMatchAll requires all tags`() = runTest {
        val doc1 = repo.createDocument("All Tags Doc").getOrThrow()
        val doc2 = repo.createDocument("Partial Tags Doc").getOrThrow()
        val work = repo.createTag("Work")
        val urgent = repo.createTag("Urgent")
        repo.setDocumentTags(doc1, listOf(work, urgent))
        repo.setDocumentTags(doc2, listOf(work))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleFilterTag(work)
        viewModel.toggleFilterTag(urgent)
        viewModel.setFilterMatchMode(MatchMode.MATCH_ALL)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.documents.size)
        assertEquals("All Tags Doc", viewModel.uiState.value.documents[0].name)
    }

    @Test
    fun `shareFilteredDocs defaults to false`() {
        assertFalse(viewModel.uiState.value.shareFilteredDocs)
        assertFalse(viewModel.uiState.value.showShareSheet)
    }

    @Test
    fun `showFilteredShareSheet sets both flags`() {
        viewModel.showFilteredShareSheet()
        assertTrue(viewModel.uiState.value.showShareSheet)
        assertTrue(viewModel.uiState.value.shareFilteredDocs)
    }

    @Test
    fun `showShareSheet resets shareFilteredDocs`() {
        viewModel.showFilteredShareSheet()
        assertTrue(viewModel.uiState.value.shareFilteredDocs)

        viewModel.showShareSheet()
        assertTrue(viewModel.uiState.value.showShareSheet)
        assertFalse(viewModel.uiState.value.shareFilteredDocs)
    }

    @Test
    fun `hideShareSheet resets both flags`() {
        viewModel.showFilteredShareSheet()
        assertTrue(viewModel.uiState.value.showShareSheet)
        assertTrue(viewModel.uiState.value.shareFilteredDocs)

        viewModel.hideShareSheet()
        assertFalse(viewModel.uiState.value.showShareSheet)
        assertFalse(viewModel.uiState.value.shareFilteredDocs)
    }

    @Test
    fun `importPdf sets error state on failure`() = runTest {
        repo.failImportPdf = true
        val uri = Uri.parse("content://test/test.pdf")

        viewModel.importPdf(uri)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Import failed", viewModel.uiState.value.importErrorMessage)
        assertFalse(viewModel.uiState.value.showImportProgress)
    }

    @Test
    fun `default sort is last seen descending`() = runTest {
        val a = repo.createDocument("A").getOrThrow()
        val b = repo.createDocument("B").getOrThrow()
        repo.setLastAccessed(a, 1_000)
        repo.setLastAccessed(b, 2_000)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "most recently seen first",
            listOf(b, a),
            viewModel.uiState.value.documents.map { it.id },
        )
    }

    @Test
    fun `reverse inverts the selected sort`() = runTest {
        val a = repo.createDocument("A").getOrThrow()
        val b = repo.createDocument("B").getOrThrow()
        repo.setLastAccessed(a, 1_000)
        repo.setLastAccessed(b, 2_000)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleSortReversed()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.sortReversed)
        assertEquals(
            "least recently seen first",
            listOf(a, b),
            viewModel.uiState.value.documents.map { it.id },
        )
    }
}
