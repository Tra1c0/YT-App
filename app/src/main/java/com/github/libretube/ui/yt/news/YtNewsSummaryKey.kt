package com.github.libretube.ui.yt.news

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.github.libretube.LibreTubeApp
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * YT: the user's own API key for the news summaries. Without it the feature is off: no request,
 * no button. Stored as YtAccount stores the session: encrypted with an AES key of the Android
 * Keystore that cannot leave the device, in its own preferences file that backups leave out
 * (yt_backup_rules, yt_data_extraction_rules) and that the app's settings export never reads.
 * The key is never logged and never shown again; diagnosis and settings only say whether one is set.
 */
object YtNewsSummaryKey {
    private const val PREFS = "yt_newssum"
    private const val KEY_ALIAS = "yt_newssum_key"
    private const val SECRET = "api_key"

    private val _present = MutableStateFlow(runCatching { prefs().contains(SECRET) }.getOrDefault(false))

    /** Whether a key is set, for the card and the settings row. */
    val present: StateFlow<Boolean> = _present.asStateFlow()

    private fun prefs() = LibreTubeApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The key, or null when none is set or it can no longer be decrypted (then it is removed). */
    fun get(): String? {
        val blob = prefs().getString(SECRET, null) ?: return null
        return runCatching { decrypt(blob) }.getOrElse {
            // the Keystore key is gone (device reset of the lock screen, restore elsewhere)
            clear()
            null
        }
    }

    fun set(apiKey: String) {
        prefs().edit(commit = true) { putString(SECRET, encrypt(apiKey.trim())) }
        _present.value = true
    }

    fun clear() {
        prefs().edit(commit = true) { remove(SECRET) }
        _present.value = false
    }

    /** For the diagnosis page: whether a key is set, never the key. */
    fun diagnosis(): String = if (_present.value) "key set" else "no key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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
