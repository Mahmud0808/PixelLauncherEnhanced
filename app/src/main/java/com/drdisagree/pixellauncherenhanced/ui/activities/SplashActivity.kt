package com.drdisagree.pixellauncherenhanced.ui.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.color.DynamicColors
import com.drdisagree.pixellauncherenhanced.utils.RootShell
import com.topjohnwu.superuser.Shell

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private var keepShowing = true
    private val runner = Runnable {
        Shell.getShell { _: Shell? ->
            val isRooted = Shell.isAppGrantedRoot() == java.lang.Boolean.TRUE

            keepShowing = false

            startActivity(
                Intent(this@SplashActivity, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_IS_ROOTED, isRooted)
                }
            )
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen: SplashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { keepShowing }
        DynamicColors.applyToActivitiesIfAvailable(application)
        Thread(runner).start()
    }

    companion object {
        init {
            RootShell.init()
        }
    }
}
