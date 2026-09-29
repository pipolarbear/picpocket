package com.picpocket.app.domain.workflow

import com.picpocket.app.domain.storage.FakeFolderAccess
import com.picpocket.app.domain.workflow.action.DefaultActionRegistry
import com.picpocket.app.domain.workflow.action.DeleteAction
import com.picpocket.app.domain.workflow.action.EncryptAction
import com.picpocket.app.domain.workflow.action.NotifyAction
import com.picpocket.app.domain.workflow.action.SaveToFolderAction
import com.picpocket.app.domain.workflow.action.SendToAppAction
import com.picpocket.app.domain.workflow.action.ZipAction
import com.picpocket.app.domain.workflow.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionRegistryTest {

    private val registry = DefaultActionRegistry(
        encrypt = EncryptAction(),
        zip = ZipAction(),
        saveToFolder = SaveToFolderAction(FakeFolderAccess()),
        sendToApp = SendToAppAction(),
        notify = NotifyAction(),
        delete = DeleteAction(),
    )

    @Test
    fun `every action type resolves to its implementation`() {
        assertEquals(ActionType.entries.toSet(), registry.all.map { it.type }.toSet())
        ActionType.entries.forEach { type ->
            assertEquals(type, registry.action(type).type)
        }
    }
}
