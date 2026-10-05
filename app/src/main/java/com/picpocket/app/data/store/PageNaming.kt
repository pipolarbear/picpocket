package com.picpocket.app.data.store

import java.io.File
import java.security.MessageDigest

object PageNaming {

    fun filenameFor(bytes: ByteArray, extension: String = "jpg"): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes), extension)

    fun filenameFor(file: File, extension: String = "jpg"): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return hex(digest.digest(), extension)
    }

    private fun hex(bytes: ByteArray, extension: String): String =
        bytes.joinToString("") { "%02x".format(it) } + ".$extension"
}
