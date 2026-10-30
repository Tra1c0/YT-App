package com.github.libretube.ui.yt.account

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import com.github.libretube.LibreTubeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the app shows of the YouTube account the user signed in with. */
data class YtAccountInfo(val name: String, val handle: String, val photo: String?)

/**
 * YT: the YouTube account of this device.
 *
 * The session (the youtube.com cookies) lives only here, encrypted with an AES key of the
 * Android Keystore that cannot leave the device. The sign-in window's own cookie store is
 * wiped after every sign-in, and both are excluded from backups. The session is only ever
 * sent to youtube.com.
 */
object YtAccount {
    private const val PREFS = "yt_account"
    private const val KEY_ALIAS = "yt_account_session"
    private const val SESSION = "session"
    private const val NAME = "name"
    private const val HANDLE = "handle"
    private const val PHOTO = "photo"
    private const val OFFERED = "offered"

    /** Work that outlives a screen: imports and the sign-out at YouTube. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(loadInfo())
    val state: StateFlow<YtAccountInfo?> = _state.asStateFlow()
    val signedIn: Boolean get() = _state.value != null

    private fun prefs() = LibreTubeApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadInfo(): YtAccountInfo? {
        val prefs = prefs()
        if (!prefs.contains(SESSION)) return null
        return YtAccountInfo(
            name = prefs.getString(NAME, null).orEmpty(),
            handle = prefs.getString(HANDLE, null).orEmpty(),
            photo = prefs.getString(PHOTO, null),
        )
    }

    /** Whether the one-time offer to sign in has been shown. */
    var offered: Boolean
        get() = prefs().getBoolean(OFFERED, false)
        set(value) = prefs().edit { putBoolean(OFFERED, value) }

    /** The decrypted session, or null when signed out or when the key is gone (e.g. a restored backup). */
    internal fun session(): String? = runCatching {
        decrypt(prefs().getString(SESSION, null) ?: return null)
    }.getOrNull()

    internal fun saveSession(cookies: String) {
        prefs().edit { putString(SESSION, encrypt(cookies)) }
    }

    /**
     * Finishes a sign-in with the cookies the sign-in window received from youtube.com: checks
     * them at YouTube and reads name, handle and photo of the account. Returns null if YouTube
     * does not accept them.
     */
    suspend fun completeSignIn(cookies: String): YtAccountInfo? = withContext(Dispatchers.IO) {
        saveSession(cookies)
        val api = YtInnerTube(cookies) { saveSession(it) }
        val loggedIn = runCatching { api.prepare() }.getOrDefault(false)
        val info = runCatching { api.accountInfo() }.getOrNull()
            ?: if (loggedIn) YtAccountInfo("", "", null) else null
        if (info == null) {
            clearLocal()
            return@withContext null
        }
        prefs().edit {
            putString(NAME, info.name)
            putString(HANDLE, info.handle)
            putString(PHOTO, info.photo)
        }
        _state.value = info
        info
    }

    /** Signs out: forgets the session here and ends it at YouTube. */
    fun signOut() {
        val cookies = session()
        clearLocal()
        clearWebView()
        if (cookies != null) {
            scope.launch { runCatching { YtInnerTube(cookies) {}.logout() } }
        }
    }

    private fun clearLocal() {
        prefs().edit {
            remove(SESSION)
            remove(NAME)
            remove(HANDLE)
            remove(PHOTO)
        }
        _state.value = null
    }

    /**
     * Wipes the sign-in window's cookie store and web storage, then runs [then]. Wiping takes a
     * moment; a page loaded before it ends could lose the cookies it just got.
     */
    fun clearWebView(then: (() -> Unit)? = null) {
        val started = runCatching {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                then?.invoke()
            }
        }.isSuccess
        if (!started) then?.invoke()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val data = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }
}

/** The activity behind a view's context, if any. */
internal fun Context.findComponentActivity(): ComponentActivity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}
