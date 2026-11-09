package com.picpocket.app.domain.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Typed, per-action parameters. */
@Serializable
sealed interface ActionParams {
    @Serializable
    @SerialName("none")
    data object None : ActionParams

    /** @property redact hide this document's redacted areas before encrypting. */
    @Serializable
    @SerialName("encrypt")
    data class Encrypt(val passphrase: String, val redact: Boolean = false) : ActionParams

    /** @property redact hide this document's redacted areas before zipping. */
    @Serializable
    @SerialName("zip")
    data class Zip(val redact: Boolean = false) : ActionParams

    /** @property redact hide this document's redacted areas before saving. */
    @Serializable
    @SerialName("save_to_folder")
    data class SaveToFolder(
        val folderUri: String,
        val displayName: String,
        val redact: Boolean = false,
    ) : ActionParams

    /** @property redact hide this document's redacted areas before sharing. */
    @Serializable
    @SerialName("send_to_app")
    data class SendToApp(
        val packageName: String,
        val displayName: String,
        val redact: Boolean = false,
    ) : ActionParams

    @Serializable
    @SerialName("notify")
    data class Notify(val message: String) : ActionParams
}
