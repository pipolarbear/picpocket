package com.picpocket.app.data.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocumentAccessStoreTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `touch records a timestamp`() {
        val store = DocumentAccessStore(context)
        store.touch("doc-1")
        assertTrue((store.snapshot()["doc-1"] ?: 0L) > 0L)
    }

    @Test
    fun `remove drops entries`() {
        val store = DocumentAccessStore(context)
        store.touch("doc-1")
        store.touch("doc-2")
        store.remove(listOf("doc-1"))
        assertFalse(store.snapshot().containsKey("doc-1"))
        assertTrue(store.snapshot().containsKey("doc-2"))
    }

    @Test
    fun `timestamps persist across instances`() {
        DocumentAccessStore(context).touch("doc-1")
        val reopened = DocumentAccessStore(context)
        assertTrue(reopened.snapshot().containsKey("doc-1"))
    }
}
