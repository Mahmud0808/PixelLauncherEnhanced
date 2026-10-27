package com.drdisagree.pixellauncherenhanced.data.enums

import com.drdisagree.pixellauncherenhanced.data.common.Constants.GESTURE_DOUBLE_TAP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.GESTURE_DOUBLE_TAP_APP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.GESTURE_PINCH_IN
import com.drdisagree.pixellauncherenhanced.data.common.Constants.GESTURE_PINCH_IN_APP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.GESTURE_PINCH_OUT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.GESTURE_PINCH_OUT_APP

enum class HomeGesture(val key: String, val appKey: String) {
    DOUBLE_TAP(GESTURE_DOUBLE_TAP, GESTURE_DOUBLE_TAP_APP),
    PINCH_IN(GESTURE_PINCH_IN, GESTURE_PINCH_IN_APP),
    PINCH_OUT(GESTURE_PINCH_OUT, GESTURE_PINCH_OUT_APP)
}
