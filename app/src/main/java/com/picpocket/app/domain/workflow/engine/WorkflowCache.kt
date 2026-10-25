package com.picpocket.app.domain.workflow.engine

import java.io.File

/**
 * The workflow cache holds the rendered PDF for each run. It is only needed
 * while a run (or a foreground share it starts) is alive, so old files are
 * removed on app start.
 */
object WorkflowCache {
    const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    const val MAX_BYTES = 200L * 1024L * 1024L

    /** Deletes files older than [MAX_AGE_MS], then the oldest over [maxBytes]. */
    fun clean(
        dir: File,
        now: Long = System.currentTimeMillis(),
        maxBytes: Long = MAX_BYTES,
    ) {
        if (!dir.isDirectory) return
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.filter { now - it.lastModified() > MAX_AGE_MS }.forEach { it.delete() }

        val remaining = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
            ?: return
        var total = remaining.sumOf { it.length() }
        for (file in remaining) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}
