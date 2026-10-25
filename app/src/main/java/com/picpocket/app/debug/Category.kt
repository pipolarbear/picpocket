package com.picpocket.app.debug

enum class Category(val defaultLevel: Level) {
    DRIVE_FILES(Level.WARN),
    UI_ACTIONS(Level.INFO),
    STORE_STATE(Level.INFO),
    DRIVE_API(Level.WARN),
    CHAT_API(Level.WARN),
    OCR(Level.INFO),
    WORKFLOW(Level.WARN),
}
