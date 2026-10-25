package com.picpocket.app.domain.storage

import android.content.Context

/** How long workflow-deleted documents stay recoverable in the local trash. */
object TrashSettings {
    const val KEY = "trash_retention_days"
    const val DEFAULT_DAYS = 7
    val options = listOf(1, 7, 30, 90)

    fun read(context: Context): Int =
        context.getSharedPreferences("settings", 0).getInt(KEY, DEFAULT_DAYS)

    fun write(context: Context, days: Int) {
        context.getSharedPreferences("settings", 0)
            .edit()
            .putInt(KEY, days.coerceAtLeast(1))
            .apply()
    }
}
