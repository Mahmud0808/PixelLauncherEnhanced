package com.drdisagree.pixellauncherenhanced.ui.activities

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.model.SettingsEntry
import com.drdisagree.pixellauncherenhanced.databinding.ActivityMainBinding
import com.drdisagree.pixellauncherenhanced.ui.base.BaseActivity
import com.drdisagree.pixellauncherenhanced.ui.fragments.AppIconEditor
import com.drdisagree.pixellauncherenhanced.utils.RootShell
import com.drdisagree.pixellauncherenhanced.ui.fragments.HomePage
import com.drdisagree.pixellauncherenhanced.ui.fragments.Updates
import com.drdisagree.pixellauncherenhanced.utils.ExpressiveDialog
import com.drdisagree.pixellauncherenhanced.utils.UpdateManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class MainActivity : BaseActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    lateinit var binding: ActivityMainBinding

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            UpdateManager.setNotifications(this, granted)
            checkForUpdates()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.getRoot())

        supportFragmentManager.registerFragmentLifecycleCallbacks(sharedElementBinder, false)

        if (savedInstanceState == null) {
            replaceFragment(supportFragmentManager, HomePage())
            openEditorFromIntent()
            openUpdatesFromIntent(intent)
        }

        if (intent.hasExtra(EXTRA_IS_ROOTED)) {
            if (intent.getBooleanExtra(EXTRA_IS_ROOTED, false)) onRootReady() else showRootFailedDialog()
        } else {
            RootShell.checkRoot { isRooted ->
                if (isFinishing) return@checkRoot
                if (isRooted) onRootReady() else showRootFailedDialog()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openUpdatesFromIntent(intent)
    }

    private fun openUpdatesFromIntent(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false)) return
        intent.removeExtra(EXTRA_OPEN_UPDATES)
        openScreen(Updates::class.java)
    }

    private fun onRootReady() {
        if (UpdateManager.shouldAskNotifications()) showNotificationDialog() else checkForUpdates()
    }

    private fun showNotificationDialog() {
        lifecycleScope.launch {
            delay(DIALOG_DELAY_MS)
            if (isFinishing || isDestroyed) return@launch
            ExpressiveDialog.show(
                context = this@MainActivity,
                icon = R.drawable.ic_notification,
                title = getString(R.string.update_permission_title),
                message = getString(R.string.update_permission_desc),
                positive = ExpressiveDialog.Action(getString(R.string.update_permission_allow)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        UpdateManager.needsNotificationPermission(this@MainActivity)
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        UpdateManager.setNotifications(this@MainActivity, true)
                        checkForUpdates()
                    }
                },
                negative = ExpressiveDialog.Action(getString(R.string.update_permission_skip)) {
                    UpdateManager.setNotifications(this@MainActivity, false)
                    checkForUpdates()
                },
                cancelable = false
            )
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch { UpdateManager.checkOnLaunch(applicationContext) }
    }

    private fun openEditorFromIntent() {
        val component = intent.getStringExtra(EXTRA_EDIT_COMPONENT)
            ?.let { ComponentName.unflattenFromString(it) } ?: return

        replaceFragment(
            supportFragmentManager,
            AppIconEditor.newInstance(
                component,
                intent.getStringExtra(EXTRA_EDIT_LABEL).orEmpty(),
                intent.getBooleanExtra(EXTRA_EDIT_HOME, false)
            )
        )
    }

    private fun showRootFailedDialog() {
        ExpressiveDialog.show(
            context = this,
            icon = R.drawable.ic_error,
            title = getString(R.string.root_connection_failed_title),
            message = getString(R.string.root_connection_failed_desc),
            positive = ExpressiveDialog.Action(getString(R.string.exit)) { exitProcess(0) },
            tone = ExpressiveDialog.Tone.ERROR,
            cancelable = false
        )
    }

    private val sharedElementBinder = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            v.setBackgroundColor(MaterialColors.getColor(v, com.google.android.material.R.attr.colorSurface))
            (v as? ViewGroup)?.isTransitionGroup = true

            val name = f.arguments?.getString(ARG_SHARED_ELEMENT) ?: return
            v.transitionName = name
            f.arguments?.remove(ARG_SHARED_ELEMENT)
        }
    }

    fun openFromDashboard(fragmentClass: Class<*>, sharedView: View) {
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) return

        val fragment = fragmentManager.fragmentFactory.instantiate(classLoader, fragmentClass.name)
        val current = fragmentManager.findFragmentById(R.id.fragmentContainerView)
        val surface = MaterialColors.getColor(sharedView, com.google.android.material.R.attr.colorSurface)

        fragment.arguments = (fragment.arguments ?: Bundle()).apply {
            putString(ARG_SHARED_ELEMENT, sharedView.transitionName)
        }
        fragment.sharedElementEnterTransition = containerTransform(surface)
        fragment.sharedElementReturnTransition = containerTransform(surface)
        fragment.enterTransition = null
        fragment.returnTransition = null
        current?.exitTransition = MaterialElevationScale(false).apply { duration = TRANSFORM_DURATION }
        current?.reenterTransition = MaterialElevationScale(true).apply { duration = TRANSFORM_DURATION }

        fragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .addSharedElement(sharedView, sharedView.transitionName)
            .replace(R.id.fragmentContainerView, fragment, fragmentClass.simpleName)
            .addToBackStack(fragmentClass.simpleName)
            .commit()
    }

    fun openScreen(fragmentClass: Class<*>) {
        replaceFragment(
            supportFragmentManager,
            supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentClass.name)
        )
    }

    fun openSetting(entry: SettingsEntry) {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, entry.fragment.name)
        fragment.arguments = (fragment.arguments ?: Bundle()).apply {
            putString(ARG_HIGHLIGHT_KEY, entry.key)
        }
        replaceFragment(supportFragmentManager, fragment)
    }

    private fun containerTransform(surface: Int) = MaterialContainerTransform().apply {
        drawingViewId = R.id.fragmentContainerView
        duration = TRANSFORM_DURATION
        scrimColor = Color.TRANSPARENT
        setAllContainerColors(surface)
    }

    @Suppress("deprecation")
    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        replaceFragment(
            supportFragmentManager,
            supportFragmentManager.fragmentFactory.instantiate(
                classLoader, pref.fragment!!
            ).apply {
                arguments = pref.extras
                setTargetFragment(caller, 0)
            }
        )
        return true
    }

    companion object {

        const val ARG_SHARED_ELEMENT = "plenhanced_shared_element"
        const val ARG_HIGHLIGHT_KEY = "plenhanced_highlight_key"
        const val EXTRA_IS_ROOTED = "isRooted"
        const val EXTRA_EDIT_COMPONENT = "plenhanced_edit_component"
        const val EXTRA_EDIT_LABEL = "plenhanced_edit_label"
        const val EXTRA_EDIT_HOME = "plenhanced_edit_home"
        const val EXTRA_OPEN_UPDATES = "plenhanced_open_updates"
        private const val TRANSFORM_DURATION = 450L
        private const val DIALOG_DELAY_MS = 700L

        fun replaceFragment(fragmentManager: FragmentManager, fragment: Fragment) {
            if (fragmentManager.isStateSaved) return

            try {
                val fragmentTag = fragment.javaClass.simpleName
                var currentFragment = fragmentManager.findFragmentById(R.id.fragmentContainerView)

                if (currentFragment != null &&
                    currentFragment.javaClass.simpleName == fragmentTag
                ) {
                    popCurrentFragment(fragmentManager)
                }

                for (i in 0 until fragmentManager.backStackEntryCount) {
                    if (fragmentManager.getBackStackEntryAt(i).name == fragmentTag) {
                        fragmentManager.popBackStack(
                            fragmentTag,
                            POP_BACK_STACK_INCLUSIVE
                        )
                        break
                    }
                }

                currentFragment = fragmentManager.findFragmentById(R.id.fragmentContainerView)
                listOfNotNull(fragment, currentFragment).forEach { it.clearTransitions() }

                fragmentManager.beginTransaction().apply {
                    setReorderingAllowed(true)
                    setCustomAnimations(
                        R.anim.push_enter,
                        R.anim.push_exit,
                        R.anim.pop_enter,
                        R.anim.pop_exit
                    )
                    replace(R.id.fragmentContainerView, fragment, fragmentTag)

                    when (fragmentTag) {
                        HomePage::class.java.simpleName -> {
                            fragmentManager.popBackStack(null, POP_BACK_STACK_INCLUSIVE)
                        }

                        else -> {
                            addToBackStack(fragmentTag)
                        }
                    }

                    commit()
                }
            } catch (_: IllegalStateException) {
            }
        }

        private fun Fragment.clearTransitions() {
            enterTransition = null
            exitTransition = null
            reenterTransition = null
            returnTransition = null
        }

        fun popCurrentFragment(fragmentManager: FragmentManager) {
            if (fragmentManager.isStateSaved) return

            fragmentManager.popBackStack()
        }
    }
}