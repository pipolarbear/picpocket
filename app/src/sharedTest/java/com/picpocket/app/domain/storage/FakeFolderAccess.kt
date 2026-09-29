package com.picpocket.app.domain.storage

import android.net.Uri

/** In-memory FolderAccess for tests. */
class FakeFolderAccess : FolderAccess {

    val persisted = mutableSetOf<String>()
    val names = mutableMapOf<String, String>()
    var takeResult: Boolean = true

    fun persist(uri: String) {
        persisted.add(uri)
    }

    override fun takePersistable(uri: Uri): Boolean {
        if (takeResult) persisted.add(uri.toString())
        return takeResult
    }

    override fun isPersisted(uri: Uri): Boolean = uri.toString() in persisted

    override fun displayName(uri: Uri): String = names[uri.toString()] ?: "Folder"
}
