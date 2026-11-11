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
 * YT: the settings of the news summaries: the chosen provider ("Anbieter", off by default), the
 * user's own API key per provider, an optional model per provider and the address of the
 * compatible one. Without a usable provider the feature is off: no request, no button.
 *
 * Keys are stored as YtAccount stores the session: encrypted with an AES key of the Android
 * Keystore that cannot leave the device, in its own preferences file that backups leave out
 * (yt_backup_rules, yt_data_extraction_rules) and that the app's settings export never reads.
 * A key is never logged and never shown again; diagnosis and settings only say whether one is set.
 */
object YtNewsSummaryKey {
    private const val PREFS = "yt_newssum"
    private const val KEY_ALIAS = "yt_newssum_key"
    private const val PROVIDER = "provider"
    private const val OFF = "off"
    private const val ADDRESS = "address"

    /** The first provider's key keeps the name it had before there was a choice. */
    private fun secretName(kind: YtNewsProviderKind) =
        if (kind == YtNewsProviderKind.ANTHROPIC) "api_key" else "api_key_${kind.id}"

    private fun modelName(kind: YtNewsProviderKind) = "model_${kind.id}"

    private val _ready = MutableStateFlow(runCatching { computeReady() }.getOrDefault(false))

    /** Whether the summaries are on (a provider is chosen and has what it needs), for the card. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _changes = MutableStateFlow(0)

    /** Counts every change of these settings, for the settings rows. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun prefs() = LibreTubeApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun changed() {
        _ready.value = computeReady()
        _changes.value += 1
    }

    private fun computeReady(): Boolean {
        val kind = provider()
        return YtNewsProviders.usable(kind, kind != null && hasKey(kind), kind?.let { model(it) }, address())
    }

    /**
     * The chosen provider, null when off. Before there was a choice, a stored key meant the
     * first provider: it stays chosen.
     */
    fun provider(): YtNewsProviderKind? {
        val prefs = prefs()
        val id = prefs.getString(PROVIDER, null)
            ?: return if (prefs.contains(secretName(YtNewsProviderKind.ANTHROPIC))) YtNewsProviderKind.ANTHROPIC else null
        return YtNewsProviderKind.of(id)
    }

    fun setProvider(kind: YtNewsProviderKind?) {
        prefs().edit(commit = true) { putString(PROVIDER, kind?.id ?: OFF) }
        changed()
    }

    fun hasKey(kind: YtNewsProviderKind): Boolean = prefs().contains(secretName(kind))

    /** The key of [kind], or null when none is set or it can no longer be decrypted (then it is removed). */
    fun get(kind: YtNewsProviderKind): String? {
        val blob = prefs().getString(secretName(kind), null) ?: return null
        return runCatching { decrypt(blob) }.getOrElse {
            // the Keystore key is gone (device reset of the lock screen, restore elsewhere)
            clear(kind)
            null
        }
    }

    fun set(kind: YtNewsProviderKind, apiKey: String) {
        prefs().edit(commit = true) { putString(secretName(kind), encrypt(apiKey.trim())) }
        changed()
    }

    fun clear(kind: YtNewsProviderKind) {
        prefs().edit(commit = true) { remove(secretName(kind)) }
        changed()
    }

    /** The model the user named for [kind]; null for the provider's default. */
    fun model(kind: YtNewsProviderKind): String? = prefs().getString(modelName(kind), null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setModel(kind: YtNewsProviderKind, model: String?) {
        val value = model?.trim().orEmpty()
        prefs().edit(commit = true) { if (value.isEmpty()) remove(modelName(kind)) else putString(modelName(kind), value) }
        changed()
    }

    /** The address of the compatible provider, as entered. */
    fun address(): String? = prefs().getString(ADDRESS, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setAddress(address: String?) {
        val value = address?.trim().orEmpty()
        prefs().edit(commit = true) { if (value.isEmpty()) remove(ADDRESS) else putString(ADDRESS, value) }
        changed()
    }

    /** Everything a request of the chosen provider needs, or null when the feature is off. Decrypts the key. */
    fun setup(): YtNewsProviderSetup? {
        val kind = provider() ?: return null
        val key = get(kind)
        val address = address()
        if (!YtNewsProviders.usable(kind, key != null, model(kind), address)) return null
        val model = YtNewsProviders.model(kind, model(kind)) ?: return null
        return YtNewsProviderSetup(kind, key.orEmpty(), model, address)
    }

    /** Who writes summaries right now, for the cache key (no key is read). Null when off. */
    fun cacheScope(): String? {
        val kind = provider() ?: return null
        val model = YtNewsProviders.model(kind, model(kind)) ?: return null
        return YtNewsProviders.cacheScope(kind, model, address())
    }

    /** For the diagnosis page: the provider and whether a key is set, never the key. */
    fun diagnosis(): String {
        val kind = provider() ?: return "off"
        return "${kind.id}, " + if (hasKey(kind)) "key set" else "no key"
    }

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
