package com.drdisagree.pixellauncherenhanced.data.enums

enum class GestureAction(val value: String) {
    NONE("none"),
    SLEEP("sleep"),
    NOTIFICATIONS("notifications"),
    QUICK_SETTINGS("quick_settings"),
    RECENTS("recents"),
    APP_DRAWER("app_drawer"),
    SCREENSHOT("screenshot"),
    FLASHLIGHT("flashlight"),
    ASSISTANT("assistant"),
    OPEN_APP("open_app");

    companion object {
        fun fromValue(value: String?): GestureAction =
            entries.firstOrNull { it.value == value } ?: NONE
    }
}
