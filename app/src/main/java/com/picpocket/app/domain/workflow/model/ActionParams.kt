package com.picpocket.app.domain.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Typed, per-action parameters. */
@Serializable
sealed interface ActionParams {
    @Serializable
    @SerialName("none")
    data object None : ActionParams

    @Serializable
    @SerialName("encrypt")
    data class Encrypt(val passphrase: String) : ActionParams

    @Serializable
    @SerialName("save_to_folder")
    data class SaveToFolder(val folderUri: String, val displayName: String) : ActionParams

    @Serializable
    @SerialName("send_to_app")
    data class SendToApp(val packageName: String, val displayName: String) : ActionParams

    @Serializable
    @SerialName("notify")
    data class Notify(val message: String) : ActionParams
}
