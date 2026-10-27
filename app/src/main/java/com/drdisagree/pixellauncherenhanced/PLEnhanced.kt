package com.drdisagree.pixellauncherenhanced

import android.app.Application
import android.content.Context
import com.drdisagree.pixellauncherenhanced.utils.UpdateManager
import com.google.android.material.color.DynamicColors
import java.lang.ref.WeakReference

class PLEnhanced : Application() {

    companion object {
        private var instance: PLEnhanced? = null
        private var contextReference: WeakReference<Context>? = null

        val appContext: Context
            get() {
                if (contextReference == null || contextReference?.get() == null) {
                    contextReference = WeakReference(
                        instance?.applicationContext ?: getInstance().applicationContext
                    )
                }
                return contextReference!!.get()!!
            }

        private fun getInstance(): PLEnhanced {
            if (instance == null) {
                instance = PLEnhanced()
            }
            return instance!!
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        contextReference = WeakReference(applicationContext)
        DynamicColors.applyToActivitiesIfAvailable(this)
        UpdateManager.schedule(this)
        UpdateManager.cleanup(this)
    }
}