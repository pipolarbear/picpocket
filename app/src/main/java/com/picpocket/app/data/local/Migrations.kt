package com.picpocket.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5 (rename release) had `tags` and `tag_automations` only. v6 added workflows
 * and run history. v7 added the deferred-run queue. v8 records which document a
 * run belongs to. Every shipped PicPocket database version has a path.
 */
val MIGRATION_5_7 = object : Migration(5, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workflows` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `json` TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workflow_runs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`workflowId` INTEGER NOT NULL, `workflowName` TEXT NOT NULL, " +
                "`trigger` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                "`finishedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `nodesJson` TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workflow_pending_runs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`workflowId` INTEGER NOT NULL, `workflowName` TEXT NOT NULL, " +
                "`trigger` TEXT NOT NULL, `documentId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workflow_pending_runs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`workflowId` INTEGER NOT NULL, `workflowName` TEXT NOT NULL, " +
                "`trigger` TEXT NOT NULL, `documentId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workflow_runs` ADD COLUMN `documentId` TEXT")
        db.execSQL("ALTER TABLE `workflow_runs` ADD COLUMN `documentName` TEXT")
        db.execSQL("ALTER TABLE `workflow_pending_runs` ADD COLUMN `documentName` TEXT")
    }
}

val MIGRATIONS = arrayOf(MIGRATION_5_7, MIGRATION_6_7, MIGRATION_7_8)
