package com.drdisagree.pixellauncherenhanced.utils

import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.model.DrawerTab

object DrawerTabsStore {

    fun load(): List<DrawerTab> = DrawerTab.parse(RPrefs.getString(DRAWER_TABS, null))

    fun save(tabs: List<DrawerTab>) {
        RPrefs.putString(DRAWER_TABS, DrawerTab.serialize(tabs))
    }

    fun update(id: String, transform: (DrawerTab) -> DrawerTab) {
        save(load().map { if (it.id == id) transform(it) else it })
    }
}
