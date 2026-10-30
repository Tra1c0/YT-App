package com.github.libretube.helpers

import android.net.Uri
import android.os.Build
import androidx.core.net.toUri

/**
 * YT: small guards for what comes from outside the app (other apps, links, shared text).
 */
object YtGuard {
    private const val OIDC_PENDING = "yt_oidc_pending"
    private const val OIDC_WINDOW_MS = 10 * 60 * 1000L
    private val TOKEN = Regex("^[A-Za-z0-9._~+/=-]{8,4096}$")
    private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    /**
     * True only in the Android emulator. The test switches of the emulator runs (a design or the
     * sign-in probe chosen by a start parameter) work there and nowhere else, so no other app on
     * a phone can use them.
     */
    val isEmulator: Boolean
        get() = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
            Build.PRODUCT.startsWith("sdk_gphone") || Build.PRODUCT.startsWith("sdk_google")

    /** Notes that the user just started a sign-in (or account deletion) at the sync server. */
    fun expectOidc(kind: String) {
        PreferenceHelper.putString(OIDC_PENDING, "$kind:${System.currentTimeMillis()}")
    }

    /**
     * True once if the user started [kind] in the last minutes. A callback link that nobody
     * asked for (sent by another app or a web page) is refused, so no one can slip their own
     * account in or sign the user out.
     */
    fun consumeOidc(kind: String): Boolean {
        val pending = PreferenceHelper.getString(OIDC_PENDING, "")
        PreferenceHelper.putString(OIDC_PENDING, "")
        val started = pending.substringAfter("$kind:", "").toLongOrNull() ?: return false
        return System.currentTimeMillis() - started in 0..OIDC_WINDOW_MS
    }

    fun isValidToken(token: String?): Boolean = token != null && TOKEN.matches(token)

    /** The first web address in shared text, e.g. "Title: https://youtu.be/…", or null. */
    fun firstUrl(text: String?): Uri? = text
        ?.let { URL.find(it)?.value }
        ?.trimEnd('.', ',', ')', ']', '"', '\'')
        ?.toUri()
        ?.takeIf { it.isHierarchical && it.host != null }

    /** Only web links leave the app from texts of others (descriptions, comments). */
    fun isWebLink(uri: Uri?): Boolean = uri != null && uri.isHierarchical &&
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true))

    /**
     * True for HTTPS, and for plain HTTP only inside the own network (localhost, private
     * addresses, .local/.lan/.home.arpa/.internal). Passwords and tokens never go elsewhere
     * unencrypted.
     */
    fun isSafeServerUrl(url: String?): Boolean {
        val parsed = url?.trim()?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme == "https") return true
        if (scheme != "http") return false
        val host = parsed.host?.lowercase()?.trim('[', ']') ?: return false
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
        IPV4.matchEntire(host)?.let { match ->
            val (a, b) = match.destructured.toList().take(2).map { it.toInt() }
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
}
