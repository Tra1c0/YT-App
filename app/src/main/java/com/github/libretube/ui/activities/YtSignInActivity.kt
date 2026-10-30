package com.github.libretube.ui.activities

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.autofill.AutofillManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtPalettes
import com.github.libretube.ui.yt.YtTheme
import com.github.libretube.ui.yt.account.YtAccount
import com.github.libretube.ui.yt.account.YtAccountAvatar
import com.github.libretube.ui.yt.account.YtAccountInfo
import com.github.libretube.ui.yt.account.YtImport
import com.github.libretube.ui.yt.account.YtImportOptionsPanel
import com.github.libretube.ui.yt.account.YtPrimaryButton
import com.github.libretube.ui.yt.account.YtSecondaryButton
import com.github.libretube.ui.yt.account.YtTakeout
import com.github.libretube.ui.yt.palette
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * YT: the sign-in window for YouTube. It shows Google's own sign-in page (accounts.google.com,
 * the address is always visible at the top), so every check Google makes stays Google's:
 * password, the confirmation on another phone, security keys, two-step codes. Android's
 * autofill works on the page, so the password manager fills in and offers to save, with the
 * fingerprint if the manager asks for it. Passkeys need a real browser and are not offered by
 * Google inside apps; for that, Google Takeout in the browser is the way (see [YtTakeout]).
 *
 * When youtube.com has set its session, the window takes the cookies, hands them to
 * [YtAccount] and wipes its own store. Google refuses some app windows; the window then tries
 * Google's desktop page once and otherwise explains the way over Takeout.
 */
class YtSignInActivity : BaseActivity() {
    private enum class Phase { WEB, FINISHING, DONE, BLOCKED, FAILED }

    private var webView: WebView? = null
    private var desktop by mutableStateOf(false)
    private var completed = false
    private var probe = false
    private val phase = mutableStateOf(Phase.WEB)
    private val progress = mutableIntStateOf(0)
    private val host = mutableStateOf("accounts.google.com")
    private val info = mutableStateOf<YtAccountInfo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // an emulator run logs what Google shows (crash-check.sh); never on a normal start
        probe = intent.getBooleanExtra(EXTRA_PROBE, false)
        val web = runCatching { WebView(this) }.getOrNull()
        if (web == null) {
            Toast.makeText(this, R.string.yt_account_no_webview, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        web.configure()
        webView = web
        val palette = YtDesign.current().palette ?: YtPalettes.YouTube
        setContentView(
            ComposeView(this).apply {
                setContent { YtTheme(palette) { Screen(palette, web) } }
            },
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val view = webView
                    if (phase.value == Phase.WEB && view != null && view.canGoBack()) view.goBack() else finish()
                }
            },
        )
        YtAccount.clearWebView { webView?.loadUrl(startUrl()) }
    }

    override fun onDestroy() {
        // a sign-in that was not finished leaves nothing behind
        if (!completed) YtAccount.clearWebView()
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configure() {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.userAgentString = userAgent()
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        webViewClient = Client()
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@YtSignInActivity.progress.intValue = newProgress
            }
        }
    }

    /** Chrome's own user agent with the WebView's Chrome version, as phone or as desktop. */
    private fun userAgent(): String {
        val base = runCatching { WebSettings.getDefaultUserAgent(this) }.getOrDefault("")
        val chrome = Regex("Chrome/(\\d+)").find(base)?.groupValues?.get(1) ?: "141"
        return if (desktop) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.0.0 Mobile Safari/537.36"
        }
    }

    private fun startUrl(): String {
        val language = Locale.getDefault().toLanguageTag()
        val app = if (desktop) "desktop" else "m"
        val next = if (desktop) "https://www.youtube.com/" else "https://m.youtube.com/"
        val signIn = "https://www.youtube.com/signin?action_handle_signin=true&app=$app&hl=$language&next=" + Uri.encode(next)
        return "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&hl=$language&continue=" +
            Uri.encode(signIn)
    }

    private fun restart(asDesktop: Boolean) {
        desktop = asDesktop
        completed = false
        phase.value = Phase.WEB
        webView?.settings?.userAgentString = userAgent()
        YtAccount.clearWebView { webView?.loadUrl(startUrl()) }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            // only web pages load in here; app links and other schemes are not followed
            if (url.scheme != "https" && url.scheme != "http") return true
            return checkSignedIn(url)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val uri = url.toUri()
            uri.host?.let { host.value = it }
            checkSignedIn(uri)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (completed) return
            if (probe) {
                view.evaluateJavascript(PROBE_JS) {
                    Log.i(TAG, "probe ${if (desktop) "desktop" else "phone"} url=${url.take(140)} page=$it")
                }
            }
            if (REJECTED.any { url.contains(it) }) {
                onRejected()
            } else {
                view.evaluateJavascript(REJECTED_JS) { if (it == "true") onRejected() }
            }
        }
    }

    /** True once youtube.com has set the session after the sign-in. */
    private fun checkSignedIn(url: Uri): Boolean {
        if (completed) return true
        val host = url.host ?: return false
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return false
        // the sign-in handler itself sets the cookies; wait for the page after it
        if (url.path?.startsWith("/signin") == true) return false
        val cookies = sessionCookies() ?: return false
        completed = true
        webView?.stopLoading()
        finishSignIn(cookies)
        return true
    }

    private fun sessionCookies(): String? {
        val raw = runCatching { CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull()
            ?: return null
        val names = raw.split(';').map { it.substringBefore('=').trim() }.toSet()
        val api = "SAPISID" in names || "__Secure-3PAPISID" in names
        val session = "SID" in names || "__Secure-3PSID" in names || "LOGIN_INFO" in names
        return raw.takeIf { api && session }
    }

    private fun finishSignIn(cookies: String) {
        phase.value = Phase.FINISHING
        // lets the password manager offer to save what was typed into Google's page
        runCatching { getSystemService(AutofillManager::class.java)?.commit() }
        lifecycleScope.launch {
            val account = YtAccount.completeSignIn(cookies)
            YtAccount.clearWebView()
            if (probe) Log.i(TAG, "probe signed-in=${account != null}")
            if (account == null) {
                completed = false
                phase.value = Phase.FAILED
            } else {
                info.value = account
                phase.value = Phase.DONE
            }
        }
    }

    private fun onRejected() {
        if (completed || phase.value != Phase.WEB) return
        if (probe) Log.i(TAG, "probe rejected as ${if (desktop) "desktop" else "phone"}")
        if (!desktop) restart(asDesktop = true) else phase.value = Phase.BLOCKED
    }

    @Composable
    private fun Screen(palette: YtPalette, web: WebView) {
        val current by phase
        Column(
            Modifier
                .fillMaxSize()
                .background(palette.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            TopBar(palette)
            val loading by progress
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (current == Phase.WEB && loading in 1..99) {
                    LinearProgressIndicator(
                        progress = { loading / 100f },
                        color = palette.accent,
                        trackColor = Color.Transparent,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                when (current) {
                    Phase.WEB -> PasskeyHint(palette, Modifier.align(Alignment.BottomCenter))
                    Phase.FINISHING -> Panel(palette) {
                        CircularProgressIndicator(color = palette.accent)
                        Text(
                            text = stringResource(R.string.yt_account_finishing),
                            color = palette.text,
                            style = TextStyle(fontFamily = palette.font, fontSize = 16.sp),
                            modifier = Modifier.padding(top = 18.dp),
                        )
                    }
                    Phase.DONE -> Panel(palette) {
                        val account by info
                        YtAccountAvatar(palette, account, 76)
                        Text(
                            text = account?.name?.takeIf { it.isNotBlank() }
                                ?.let { stringResource(R.string.yt_account_hello, it) }
                                ?: stringResource(R.string.yt_account_row_title),
                            color = palette.text,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 24.sp),
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        account?.handle?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                color = palette.textSecondary,
                                style = TextStyle(fontFamily = palette.font, fontSize = 14.sp),
                            )
                        }
                        Spacer(Modifier.height(22.dp))
                        YtImportOptionsPanel(palette) { options ->
                            YtImport.fromAccount(options)
                            finish()
                        }
                        Text(
                            text = stringResource(R.string.yt_account_later),
                            color = palette.textSecondary,
                            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .clickable { finish() }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    Phase.BLOCKED -> Panel(palette) {
                        Message(palette, stringResource(R.string.yt_account_blocked_title), stringResource(R.string.yt_account_blocked_text))
                        YtPrimaryButton(palette, stringResource(R.string.yt_account_takeout_open), R.drawable.ic_backup) {
                            YtTakeout.openInBrowser(this@YtSignInActivity)
                            finish()
                        }
                        Spacer(Modifier.height(10.dp))
                        YtSecondaryButton(palette, stringResource(R.string.yt_account_retry), Modifier.fillMaxWidth()) {
                            restart(asDesktop = false)
                        }
                    }
                    Phase.FAILED -> Panel(palette) {
                        Message(palette, stringResource(R.string.yt_account_title), stringResource(R.string.yt_account_failed))
                        YtPrimaryButton(palette, stringResource(R.string.yt_account_retry), R.drawable.ic_login_filled) {
                            restart(asDesktop = desktop)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TopBar(palette: YtPalette) {
        val address by host
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { finish() },
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel), tint = palette.text, modifier = Modifier.size(22.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.yt_account_sign_in),
                    color = palette.text,
                    maxLines = 1,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                )
                // the real address, so it is clear that this is Google's page
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_locked), contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = address,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = palette.font, fontSize = 12.sp),
                    )
                }
            }
            Text(
                text = stringResource(if (desktop) R.string.yt_account_phone_page else R.string.yt_account_desktop),
                color = palette.textSecondary,
                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { restart(asDesktop = !desktop) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }

    @Composable
    private fun PasskeyHint(palette: YtPalette, modifier: Modifier) {
        var shown by remember { mutableStateOf(true) }
        if (!shown) return
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .padding(12.dp)
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(palette.surface)
                .clickable { shown = false }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.yt_account_passkey_hint),
                color = palette.text,
                style = TextStyle(fontFamily = palette.font, fontSize = 12.sp, lineHeight = 16.sp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(16.dp))
        }
    }

    @Composable
    private fun Panel(palette: YtPalette, content: @Composable () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .clickable(interactionSource = null, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            content()
        }
    }

    @Composable
    private fun Message(palette: YtPalette, title: String, text: String) {
        Text(
            text = title,
            color = palette.text,
            textAlign = TextAlign.Center,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 21.sp),
        )
        Text(
            text = text,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            style = TextStyle(fontFamily = palette.font, fontSize = 15.sp, lineHeight = 21.sp),
            modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
        )
    }

    companion object {
        private const val TAG = "YtSignIn"
        const val EXTRA_PROBE = "yt_probe"
        private val REJECTED = listOf("/signin/rejected", "disallowed_useragent")
        private const val PROBE_JS =
            "(function(){var b=document.body?document.body.innerText:'';return (document.title+' | '+b.replace(/\\s+/g,' ')).substring(0,300);})()"
        private const val REJECTED_JS =
            "(function(){var b=document.body?document.body.innerText:'';return /may not be secure|möglicherweise nicht sicher|disallowed_useragent/i.test(b);})()"
    }
}
