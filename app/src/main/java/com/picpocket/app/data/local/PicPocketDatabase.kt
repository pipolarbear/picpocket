package com.picpocket.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.picpocket.app.data.local.dao.TagDao
import com.picpocket.app.data.local.dao.WorkflowDao
import com.picpocket.app.data.local.dao.WorkflowPendingRunDao
import com.picpocket.app.data.local.dao.WorkflowRunDao
import com.picpocket.app.data.local.entity.TagEntity
import com.picpocket.app.data.local.entity.WorkflowEntity
import com.picpocket.app.data.local.entity.WorkflowPendingRunEntity
import com.picpocket.app.data.local.entity.WorkflowRunEntity

@Database(
    entities = [
        TagEntity::class,
        WorkflowEntity::class,
        WorkflowRunEntity::class,
        WorkflowPendingRunEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class PicPocketDatabase : RoomDatabase() {
    abstract fun tagDao(): TagDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun workflowRunDao(): WorkflowRunDao
    abstract fun workflowPendingRunDao(): WorkflowPendingRunDao
}
