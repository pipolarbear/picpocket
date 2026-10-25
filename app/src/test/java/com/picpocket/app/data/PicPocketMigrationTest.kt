package com.picpocket.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.local.MIGRATIONS
import com.picpocket.app.data.local.PicPocketDatabase
import com.picpocket.app.data.local.entity.WorkflowEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PicPocketMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `v7 database keeps runs and adds document columns`() {
        seed(
            version = 7,
            schema = v7Schema,
            data = listOf(
                "INSERT INTO tags (name, colorIndex) VALUES ('receipts', 0)",
                "INSERT INTO workflow_runs " +
                    "(workflowId, workflowName, trigger, startedAt, finishedAt, status, nodesJson) " +
                    "VALUES (1, 'reciepts', 'doc.tagged', 0, 1, 'SUCCESS', '[]')",
            ),
        )

        val db = open()
        try {
            val run = runBlocking { db.workflowRunDao().observeByWorkflow(1).first().single() }
            assertEquals("reciepts", run.workflowName)
            assertNull("old runs have no document name", run.documentName)
            assertEquals(1, runBlocking { db.tagDao().getAll() }.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v5 tags survive and the workflow tables are created`() {
        seed(
            version = 5,
            schema = v5Schema,
            data = listOf("INSERT INTO tags (name, colorIndex) VALUES ('receipts', 0)"),
        )

        val db = open()
        try {
            val tags = runBlocking { db.tagDao().getAll() }
            assertEquals(1, tags.size)
            assertEquals("receipts", tags.single().name)

            val id = runBlocking {
                db.workflowDao().upsert(WorkflowEntity(name = "w", enabled = true, json = "{}"))
            }
            assertEquals(1L, id)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v6 database migrates through the queue table to the current version`() {
        seed(version = 6, schema = v6Schema)

        val db = open()
        try {
            assertEquals(0, runBlocking { db.tagDao().getAll() }.size)
        } finally {
            db.close()
        }
    }

    private fun open(): PicPocketDatabase =
        Room.databaseBuilder(context, PicPocketDatabase::class.java, dbName)
            .addMigrations(*MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }

    private fun seed(version: Int, schema: List<String>, data: List<String> = emptyList()) {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        schema.forEach { db.execSQL(it) }
        data.forEach { db.execSQL(it) }
        db.version = version
        db.close()
    }

    private companion object {
        const val TAGS =
            "CREATE TABLE IF NOT EXISTS `tags` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL)"

        const val TAG_AUTOMATIONS =
            "CREATE TABLE IF NOT EXISTS `tag_automations` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tagId` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, `configJson` TEXT NOT NULL, `triggerEvent` TEXT NOT NULL)"

        const val WORKFLOWS =
            "CREATE TABLE IF NOT EXISTS `workflows` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `json` TEXT NOT NULL)"

        const val WORKFLOW_RUNS =
            "CREATE TABLE IF NOT EXISTS `workflow_runs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workflowId` INTEGER NOT NULL, " +
                "`workflowName` TEXT NOT NULL, `trigger` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, `nodesJson` TEXT NOT NULL)"

        const val PENDING_RUNS =
            "CREATE TABLE IF NOT EXISTS `workflow_pending_runs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workflowId` INTEGER NOT NULL, " +
                "`workflowName` TEXT NOT NULL, `trigger` TEXT NOT NULL, " +
                "`documentId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"

        val v5Schema = listOf(TAGS, TAG_AUTOMATIONS)
        val v6Schema = listOf(TAGS, WORKFLOWS, WORKFLOW_RUNS)
        val v7Schema = listOf(TAGS, WORKFLOWS, WORKFLOW_RUNS, PENDING_RUNS)
    }
}
