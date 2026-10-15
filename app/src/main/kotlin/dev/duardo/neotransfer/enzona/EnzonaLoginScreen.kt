package dev.duardo.neotransfer.enzona

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.duardo.neotransfer.PageHeader
import dev.duardo.neotransfer.core.enzona.EnzonaAuthContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Root sets FLAG_SECURE while visible; URL/code are never saved, logged or sent to an Intent. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun EnzonaLoginScreen(url: String, onCallback: (String) -> Unit, onCancel: () -> Unit) {
    val callback by rememberUpdatedState(onCallback)
    val cancel by rememberUpdatedState(onCancel)
    BackHandler { cancel() }
    key(url) {
        var notice by remember { mutableStateOf<String?>(null) }
        var waiting by remember { mutableStateOf(true) }
        val delivered = remember { AtomicBoolean(false) }
        Column(Modifier.fillMaxSize()) {
            PageHeader("ENZONA", { cancel() })
            notice?.let {
                Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            isSaveEnabled = false
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                javaScriptCanOpenWindowsAutomatically = false
                                setSupportMultipleWindows(false)
                                safeBrowsingEnabled = true
                                cacheMode = WebSettings.LOAD_NO_CACHE
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            setDownloadListener { _, _, _, _, _ ->
                                notice = "Las descargas no están disponibles durante el acceso"
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                                override fun onGeolocationPermissionsShowPrompt(origin: String?, prompt: GeolocationPermissions.Callback) {
                                    prompt.invoke(origin, false, false)
                                }
                                override fun onShowFileChooser(view: WebView, result: ValueCallback<Array<android.net.Uri>>,
                                    params: FileChooserParams): Boolean {
                                    result.onReceiveValue(null)
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                private fun deliver(view: WebView, value: String) {
                                    view.stopLoading()
                                    if (delivered.compareAndSet(false, true)) {
                                        waiting = true
                                        callback(value)
                                    }
                                }
                                private fun reject(view: WebView) {
                                    view.stopLoading()
                                    waiting = false
                                    notice = "No se pudo abrir la página de acceso a ENZONA"
                                }
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val value = request.url.toString()
                                    if (EnzonaAuthContract.isCallbackUri(value)) {
                                        if (request.isForMainFrame) deliver(view, value)
                                        return true
                                    }
                                    if (!isEnzonaLoginNavigation(value)) {
                                        if (request.isForMainFrame) reject(view)
                                        return true
                                    }
                                    return delivered.get()
                                }
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val value = request.url.toString()
                                    // Runs on a WebView worker. No callback (including a subframe) reaches HTTP.
                                    if (EnzonaAuthContract.isCallbackUri(value)) {
                                        if (request.isForMainFrame) view.post { deliver(view, value) }
                                        return blockedWebResponse()
                                    }
                                    if (!request.url.scheme.equals("https", ignoreCase = true) ||
                                        (request.isForMainFrame && !isEnzonaLoginNavigation(value))) {
                                        if (request.isForMainFrame) view.post { reject(view) }
                                        return blockedWebResponse()
                                    }
                                    return null // Normal HTTPS resources; never add bearer headers or a JS bridge.
                                }
                                override fun onPageStarted(view: WebView, value: String?, favicon: android.graphics.Bitmap?) {
                                    if (value != null && EnzonaAuthContract.isCallbackUri(value)) deliver(view, value)
                                    else if (value == null || !isEnzonaLoginNavigation(value)) reject(view)
                                    else waiting = true
                                }
                                override fun onPageFinished(view: WebView, value: String?) {
                                    if (!delivered.get()) waiting = false
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) reject(view)
                                }
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (request.isForMainFrame) reject(view)
                                }
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    handler.cancel()
                                    reject(view)
                                }
                                override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler,
                                    host: String?, realm: String?) {
                                    handler.cancel()
                                    reject(view)
                                }
                            }
                            if (isEnzonaLoginNavigation(url)) loadUrl(url)
                            else {
                                waiting = false
                                notice = "No se pudo abrir la página de acceso a ENZONA"
                            }
                        }
                    },
                    onRelease = { view ->
                        delivered.set(true)
                        view.stopLoading()
                        view.webChromeClient = null
                        view.webViewClient = WebViewClient()
                        view.clearHistory()
                        view.removeAllViews()
                        view.destroy()
                    },
                    update = {}, // Load once in factory; recomposition/back must not restart authorization.
                )
                if (waiting && notice == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

internal fun isEnzonaLoginNavigation(value: String): Boolean = try {
    val uri = URI(value)
    !uri.isOpaque && uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("identity.enzona.net", ignoreCase = true) && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == 443)
} catch (_: Exception) { false }

private fun blockedWebResponse() = WebResourceResponse("text/plain", "UTF-8", 403,
    "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/**
 * Explicit LOCAL logout only, after root removes the WebView and calls disconnectLocal.
 * Android's CookieManager/WebStorage are app-wide: root must reserve this WebView jar for ENZONA
 * (the current app has no other WebViews). Do not call on rotation, cancel, close or login start.
 * If root introduces another web service, isolate its jar before using this all-cookie API.
 * Completing this helper does not revoke server tokens or terminate remote SSO sessions.
 */
internal suspend fun clearEnzonaWebSession(): Unit = withContext(Dispatchers.Main.immediate) {
    WebStorage.getInstance().deleteAllData()
    suspendCancellableCoroutine { continuation ->
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            cookies.flush()
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
}
