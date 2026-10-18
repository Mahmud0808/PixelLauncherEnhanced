package com.drdisagree.pixellauncherenhanced.utils

import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_FOLDERS
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.model.DrawerFolder

object DrawerFoldersStore {

    fun load(): List<DrawerFolder> = DrawerFolder.parse(RPrefs.getString(DRAWER_FOLDERS, null))

    fun load(tabId: String?): List<DrawerFolder> = load().filter { it.belongsTo(tabId) }

    fun save(folders: List<DrawerFolder>) {
        RPrefs.putString(DRAWER_FOLDERS, DrawerFolder.serialize(folders))
    }

    fun saveOrder(tabId: String?, ordered: List<DrawerFolder>) {
        val others = load().filterNot { it.belongsTo(tabId) }
        save(others + ordered)
    }

    fun create(title: String, tabId: String?): DrawerFolder {
        val folders = load()
        val folder = DrawerFolder((folders.maxOfOrNull { it.id } ?: 0) + 1, title, tabId = tabId)
        save(folders + folder)
        return folder
    }

    fun update(id: Int, transform: (DrawerFolder) -> DrawerFolder) {
        save(load().map { if (it.id == id) transform(it) else it })
    }

    fun delete(id: Int) {
        save(load().filterNot { it.id == id })
    }

    fun deleteForTab(tabId: String) {
        save(load().filterNot { it.tabId == tabId })
    }

    fun toggleApp(id: Int, packageName: String, selected: Boolean) {
        val folders = load()
        val tabId = folders.firstOrNull { it.id == id }?.tabId
        save(folders.map { folder ->
            when {
                folder.id == id && selected -> folder.copy(apps = folder.apps - packageName + packageName)
                folder.id == id -> folder.copy(apps = folder.apps - packageName)
                selected && folder.belongsTo(tabId) -> folder.copy(apps = folder.apps - packageName)
                else -> folder
            }
        })
    }
}
