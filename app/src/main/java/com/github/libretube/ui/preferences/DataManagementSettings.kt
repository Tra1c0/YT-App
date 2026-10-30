package com.github.libretube.ui.preferences

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.enums.SyncServerType
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.dialogs.DeleteAccountDialog
import com.github.libretube.ui.dialogs.LoginDialog
import com.github.libretube.ui.dialogs.LogoutDialog
import com.github.libretube.ui.dialogs.SelectInstanceDialog
import com.github.libretube.ui.activities.YtSignInActivity
import com.github.libretube.ui.yt.account.YtAccount
import com.github.libretube.ui.yt.account.YtAccountSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.libretube.ui.views.ButtonGroupPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class DataManagementSettings : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.account_settings, rootKey)

        val authInstance = findPreference<ListPreference>(PreferenceKeys.AUTH_INSTANCE)!!

        authInstance.summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
            preference.value
        }

        authInstance.setOnPreferenceChangeListener { _, _ ->
            RetrofitInstance.apiLazyMgr.reset()
            logoutAndUpdateUI(true)
            true
        }

        val login = findPreference<Preference>(PreferenceKeys.LOGIN_REGISTER)
        val logout = findPreference<Preference>(PreferenceKeys.LOGOUT)
        val deleteAccount = findPreference<Preference>(PreferenceKeys.DELETE_ACCOUNT)

        childFragmentManager.setFragmentResultListener(
            INSTANCE_DIALOG_REQUEST_KEY,
            this
        ) { _, resultBundle ->
            val isLoggedIn = resultBundle.getBoolean(IntentData.loginTask)
            val isLoggedOut = resultBundle.getBoolean(IntentData.logoutTask)
            if (isLoggedIn) {
                toggleAuthAccountActionsUI(true)
            } else if (isLoggedOut) {
                logoutAndUpdateUI(true)
            }
        }

        login?.setOnPreferenceClickListener {
            LoginDialog().show(childFragmentManager, LoginDialog::class.java.name)
            true
        }

        logout?.setOnPreferenceClickListener {
            LogoutDialog().show(childFragmentManager, LogoutDialog::class.java.name)
            true
        }

        deleteAccount?.setOnPreferenceClickListener {
            DeleteAccountDialog()
                .show(childFragmentManager, DeleteAccountDialog::class.java.name)
            true
        }

        val syncServerType = findPreference<ButtonGroupPreference>(PreferenceKeys.SYNC_SERVER_TYPE)!!
        val libretubeSyncServerInstance = findPreference<EditTextPreference>(PreferenceKeys.LIBRETUBE_SYNC_SERVER_URL)!!

        authInstance.isVisible = syncServerType.value == SyncServerType.PIPED.name.lowercase()
        libretubeSyncServerInstance.isVisible = syncServerType.value == SyncServerType.LIBRETUBE.name.lowercase()
        toggleAuthAccountActionsUI(syncServerType.value != SyncServerType.NONE.name.lowercase())
        syncServerType.setOnPreferenceChangeListener { _, newValue ->
            authInstance.isVisible = newValue == SyncServerType.PIPED.name.lowercase()
            libretubeSyncServerInstance.isVisible = newValue == SyncServerType.LIBRETUBE.name.lowercase()

            logoutAndUpdateUI(newValue != SyncServerType.NONE.name.lowercase())
            RetrofitInstance.apiLazyMgr.reset()
            true
        }

        libretubeSyncServerInstance.setOnPreferenceChangeListener { _, newValue ->
            // validate that the input is an actual URL
            if (newValue.toString().toHttpUrlOrNull() == null)  {
                context?.toastFromMainThread(R.string.invalid_url)
                return@setOnPreferenceChangeListener false
            }
            // YT: no account data in the clear across the internet
            if (!com.github.libretube.helpers.YtGuard.isSafeServerUrl(newValue.toString())) {
                context?.toastFromMainThread(R.string.yt_insecure_server)
                return@setOnPreferenceChangeListener false
            }

            logoutAndUpdateUI(true)
            true
        }

        // YT: the YouTube account
        findPreference<Preference>(YT_ACCOUNT)?.setOnPreferenceClickListener {
            if (YtAccount.signedIn) {
                YtAccountSheet.show(requireActivity())
            } else {
                startActivity(Intent(requireContext(), YtSignInActivity::class.java))
            }
            true
        }
        findPreference<Preference>(YT_ACCOUNT_IMPORT)?.setOnPreferenceClickListener {
            YtAccountSheet.show(requireActivity(), focus = YtAccountSheet.Focus.IMPORT)
            true
        }
        findPreference<Preference>(YT_ACCOUNT_TAKEOUT)?.setOnPreferenceClickListener {
            YtAccountSheet.show(requireActivity(), focus = YtAccountSheet.Focus.TAKEOUT)
            true
        }
        findPreference<Preference>(YT_ACCOUNT_SIGN_OUT)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.yt_account_sign_out)
                .setMessage(R.string.yt_account_sign_out_question)
                .setPositiveButton(R.string.yt_account_sign_out) { _, _ -> YtAccount.signOut() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }

        // clear search history when history is disabled
        val searchHistory = findPreference<Preference>(PreferenceKeys.SEARCH_HISTORY_TOGGLE)
        searchHistory?.onPreferenceChangeListener = { _, newValue ->
            if (!(newValue as Boolean)) {
                lifecycleScope.launch(Dispatchers.IO) { Database.searchHistoryDao().deleteAll() }
            }
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            YtAccount.state.collect { account ->
                findPreference<Preference>(YT_ACCOUNT)?.summary = when {
                    account == null -> getString(R.string.yt_account_pref_summary)
                    account.name.isNotBlank() -> getString(R.string.yt_account_signed_in_as, account.name)
                    else -> getString(R.string.yt_account_signed_in_as, account.handle)
                }
                findPreference<Preference>(YT_ACCOUNT_IMPORT)?.isVisible = account != null
                findPreference<Preference>(YT_ACCOUNT_SIGN_OUT)?.isVisible = account != null
            }
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == PreferenceKeys.AUTH_INSTANCE) {
            showInstanceSelectionDialog(preference as ListPreference)
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun onDestroy() {
        @Suppress("DEPRECATION")
        if (UserDataRepositoryHelper.userDataRepository.requiresLogin && PreferenceHelper.getToken().isEmpty()) {
            context?.toastFromMainThread(R.string.missing_auth_falling_back)
            PreferenceHelper.putString(PreferenceKeys.SYNC_SERVER_TYPE, SyncServerType.NONE.name.lowercase())
        }

        super.onDestroy()
    }

    private fun showInstanceSelectionDialog(preference: ListPreference) {
        SelectInstanceDialog()
            .apply {
                arguments = bundleOf(
                    SelectInstanceDialog.SELECT_INSTANCE_TITLE_EXTRA to getString(R.string.auth_instance),
                    SelectInstanceDialog.SELECT_INSTANCE_CURRENT_INSTANCE_API_URL_EXTRA to preference.value
                )
            }
            .show(childFragmentManager, null)
        childFragmentManager.setFragmentResultListener(
            SelectInstanceDialog.SELECT_INSTANCE_RESULT_KEY,
            this
        ) { _, bundle ->
            val apiUrl =
                bundle.getString(SelectInstanceDialog.SELECT_INSTANCE_CURRENT_INSTANCE_API_URL_EXTRA)
            preference.value = apiUrl
            resetForNewInstance()
            childFragmentManager.clearFragmentResultListener(SelectInstanceDialog.SELECT_INSTANCE_RESULT_KEY)
        }
    }

    fun toggleAuthAccountActionsUI(hasAuthSupport: Boolean) {
        val loggedIn = PreferenceHelper.getToken().isNotBlank()

        findPreference<Preference>(PreferenceKeys.LOGIN_REGISTER)?.isVisible = !loggedIn && hasAuthSupport
        findPreference<Preference>(PreferenceKeys.LOGOUT)?.isVisible = loggedIn && hasAuthSupport
        findPreference<Preference>(PreferenceKeys.DELETE_ACCOUNT)?.isVisible = loggedIn && hasAuthSupport
    }

    private fun logoutAndUpdateUI(hasAuthSupport: Boolean) {
        if (PreferenceHelper.getToken().isNotBlank()) {
            Toast.makeText(context, getString(R.string.loggedout), Toast.LENGTH_SHORT).show()
            PreferenceHelper.setToken("")
        }
        toggleAuthAccountActionsUI(hasAuthSupport)
    }

    private fun resetForNewInstance() {
        RetrofitInstance.apiLazyMgr.reset()
        ActivityCompat.recreate(requireActivity())
    }

    companion object {
        const val INSTANCE_DIALOG_REQUEST_KEY = "instance_dialog_request_key"
        private const val YT_ACCOUNT = "yt_account"
        private const val YT_ACCOUNT_IMPORT = "yt_account_import"
        private const val YT_ACCOUNT_TAKEOUT = "yt_account_takeout"
        private const val YT_ACCOUNT_SIGN_OUT = "yt_account_sign_out"
    }
}
