package com.drdisagree.pixellauncherenhanced.utils

import androidx.annotation.StringRes

class BackupException(
    @param:StringRes val messageRes: Int,
    vararg val formatArgs: Any
) : Exception()
