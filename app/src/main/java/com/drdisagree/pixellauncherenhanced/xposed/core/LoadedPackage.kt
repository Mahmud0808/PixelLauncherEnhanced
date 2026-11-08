package com.drdisagree.pixellauncherenhanced.xposed.core

data class LoadedPackage(
    val packageName: String,
    val classLoader: ClassLoader,
    val processName: String
)
