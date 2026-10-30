package com.github.libretube.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.github.libretube.R
import com.github.libretube.databinding.ActivitySettingsBinding
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.preferences.DataManagementSettings

class SettingsActivity : BaseActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        val navController = binding.settings.getFragment<NavHostFragment>().navController
        setSupportActionBar(binding.toolbar)
        setContentView(binding.root)

        // ensure that the toolbar's back button is always visible
        val appBarConfiguration = AppBarConfiguration.Builder()
            .setFallbackOnNavigateUpListener {
                finish()
                true
            }
            .build()
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)

        if (intent.extras?.getString(REDIRECT_KEY) == REDIRECT_TO_INTENT_SETTINGS) {
            navController.navigate(R.id.action_global_instanceSettings)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // YT: a callback counts only if the user just started that sign-in or deletion here;
        // links from other apps or web pages cannot set or clear the account
        val data = intent.data ?: return
        when (data.host) {
            "login_callback" -> {
                val token = runCatching { data.getQueryParameter("token") }.getOrNull()
                if (!com.github.libretube.helpers.YtGuard.consumeOidc("login") ||
                    !com.github.libretube.helpers.YtGuard.isValidToken(token)
                ) {
                    toastFromMainThread(R.string.error)
                    return
                }
                PreferenceHelper.setToken(token!!)
            }
            "delete_callback" -> {
                if (!com.github.libretube.helpers.YtGuard.consumeOidc("delete")) return
                PreferenceHelper.setToken("")
            }
            else -> return
        }

        // update visible login/logout settings
        binding.settings.getFragment<NavHostFragment>().childFragmentManager.fragments.filterIsInstance<DataManagementSettings>()
            .firstOrNull()?.toggleAuthAccountActionsUI(true)
    }

    companion object {
        const val REDIRECT_KEY = "redirect"
        const val REDIRECT_TO_INTENT_SETTINGS = "intent_settings"
    }
}
