package com.walkman.tv.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.store.FoliaBackground
import com.walkman.tv.data.store.FoliaRenderQuality
import com.walkman.tv.data.store.FoliaTemplate
import com.walkman.tv.playback.LyricLine
import com.walkman.tv.ui.appContainer
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "FoliaWebViewStage"
private const val FOLIA_ORIGIN_HOST = "appassets.androidplatform.net"
private const val FOLIA_ASSET_PREFIX = "/assets/folia/"
private const val FOLIA_ENTRY_URL = "https://$FOLIA_ORIGIN_HOST${FOLIA_ASSET_PREFIX}tv-stage.html"

/**
 * Plan B WebView GPU stage hosting the genuine `whycfawa/folia-android` visualizer engine
 * (PixiJS v8 + Three.js + GLSL shaders + all 18 official templates & 6 backgrounds).
 *
 * Served offline from `assets/folia/` via Android's reserved `appassets.androidplatform.net`
 * virtual HTTPS origin so ES modules, dynamic chunks, WebGL2 shaders, and fetch() work offline.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FoliaWebViewStage(
    track: Track,
    lyrics: List<LyricLine>,
    template: FoliaTemplate,
    background: FoliaBackground,
    beatReactive: Boolean,
    glowEnabled: Boolean,
    filmGrain: Boolean,
    renderQuality: FoliaRenderQuality,
    showBottomSubtitle: Boolean,
    isDaylight: Boolean,
    bgStatic: Boolean,
    fontStyle: com.walkman.tv.data.store.FoliaFontStyle,
    animIntensity: com.walkman.tv.data.store.FoliaAnimIntensity,
    fontScale: com.walkman.tv.data.store.FoliaFontScale,
    moodLabel: String,
    isPlaying: Boolean,
    accentColor: Color,
    brightColor: Color,
    orbPrimary: Color,
    orbSecondary: Color,
    modifier: Modifier = Modifier,
) {
    val controller = appContainer.playbackController
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var jsReady by remember { mutableStateOf(false) }
    var stageError by remember { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val stateJson = remember(
        track.id,
        track.name,
        track.singer,
        track.picURL,
        lyrics,
        template,
        background,
        beatReactive,
        glowEnabled,
        filmGrain,
        renderQuality,
        showBottomSubtitle,
        isDaylight,
        bgStatic,
        fontStyle,
        animIntensity,
        fontScale,
        moodLabel,
        accentColor,
        brightColor,
        orbPrimary,
        orbSecondary,
    ) {
        buildStateJson(
            track = track,
            lyrics = lyrics,
            template = template,
            background = background,
            beatReactive = beatReactive,
            glowEnabled = glowEnabled,
            filmGrain = filmGrain,
            renderQuality = renderQuality,
            showBottomSubtitle = showBottomSubtitle,
            isDaylight = isDaylight,
            bgStatic = bgStatic,
            fontStyle = fontStyle,
            animIntensity = animIntensity,
            fontScale = fontScale,
            moodLabel = moodLabel,
            accent = accentColor,
            bright = brightColor,
            orb1 = if (orbPrimary.alpha > 0.05f) orbPrimary else Color(0xFF1E3A8A),
            orb2 = if (orbSecondary.alpha > 0.05f) orbSecondary else Color(0xFF312E81),
        )
    }

    LaunchedEffect(pageReady, jsReady, stateJson, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (!pageReady && !jsReady) return@LaunchedEffect
        val safeJson = stateJson
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        wv.evaluateJavascript(
            "if(window.__foliaState){window.__foliaState($safeJson);}else{window.__foliaPendingState=$safeJson;}",
            null,
        )
        val pos = controller.player.currentPosition.coerceAtLeast(0L)
        wv.evaluateJavascript(
            "if(window.__foliaTick){window.__foliaTick($pos, $isPlaying, 0);}",
            null,
        )
    }

    LaunchedEffect(pageReady, jsReady, isPlaying, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (!pageReady && !jsReady) return@LaunchedEffect
        while (true) {
            val pos = controller.player.currentPosition.coerceAtLeast(0L)
            val level = controller.audioLevel.value.coerceIn(0f, 1f)
            wv.evaluateJavascript(
                "if(window.__foliaTick){window.__foliaTick($pos, $isPlaying, $level);}",
                null,
            )
            delay(if (isPlaying) 50L else 250L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                removeJavascriptInterface("FoliaNative")
                stopLoading()
                onPause()
                clearHistory()
                loadUrl("about:blank")
                destroy()
            }
            webViewRef = null
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // IMPORTANT: Do NOT force LAYER_TYPE_HARDWARE here — WebView uses Chromium's
                    // native GPU compositor by default, and forcing an offscreen hardware layer
                    // breaks WebGL canvas compositing (black screen) on many Android TV GPUs.
                    setBackgroundColor(android.graphics.Color.parseColor("#070A12"))
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                    isLongClickable = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mediaPlaybackRequiresUserGesture = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onReady() {
                                mainHandler.post { jsReady = true }
                            }
                        },
                        "FoliaNative",
                    )
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                Log.e(TAG, "JS ERROR: ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                                mainHandler.post {
                                    if (stageError == null && !msg.message().contains("ReadPixels")) {
                                        stageError = msg.message()
                                    }
                                }
                            }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val uri = request?.url ?: return null
                            if (uri.host != FOLIA_ORIGIN_HOST) return null
                            return serveFoliaAsset(ctx, uri.path ?: "${FOLIA_ASSET_PREFIX}tv-stage.html")
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady = true
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                val desc = error?.description?.toString() ?: "load error"
                                Log.e(TAG, "WebView main frame error: $desc")
                                mainHandler.post { stageError = desc }
                            }
                        }
                    }
                    webViewRef = this
                    loadUrl(FOLIA_ENTRY_URL)
                }
            },
        )

        stageError?.let { err ->
            Text(
                text = "Folia WebView 诊断：$err",
                color = Color(0xFFFCA5A5),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
    }
}

private fun serveFoliaAsset(context: Context, rawPath: String): WebResourceResponse {
    val clean = when {
        rawPath.startsWith(FOLIA_ASSET_PREFIX) -> rawPath.removePrefix(FOLIA_ASSET_PREFIX)
        else -> rawPath.trimStart('/')
    }.ifEmpty { "tv-stage.html" }
    val assetPath = "folia/$clean"
    val headers = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "public, max-age=31536000, immutable",
    )
    return try {
        val stream = context.assets.open(assetPath)
        val mime = guessMimeType(clean)
        val encoding = if (isTextMime(mime)) "UTF-8" else null
        WebResourceResponse(mime, encoding, 200, "OK", headers, stream)
    } catch (_: Throwable) {
        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", headers, null)
    }
}

private fun isTextMime(mime: String): Boolean =
    mime.startsWith("text/") ||
        mime == "application/javascript" ||
        mime == "application/json" ||
        mime == "image/svg+xml"

private fun guessMimeType(path: String): String {
    val lower = path.lowercase()
    return when {
        lower.endsWith(".html") -> "text/html"
        lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript"
        lower.endsWith(".css") -> "text/css"
        lower.endsWith(".json") -> "application/json"
        lower.endsWith(".svg") -> "image/svg+xml"
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".woff2") -> "font/woff2"
        lower.endsWith(".woff") -> "font/woff"
        lower.endsWith(".ttf") -> "font/ttf"
        lower.endsWith(".wasm") -> "application/wasm"
        else -> "application/octet-stream"
    }
}

private fun Color.toHexRgb(): String {
    val rgb = toArgb() and 0xFFFFFF
    return String.format("#%06X", rgb)
}

private fun buildStateJson(
    track: Track,
    lyrics: List<LyricLine>,
    template: FoliaTemplate,
    background: FoliaBackground,
    beatReactive: Boolean,
    glowEnabled: Boolean,
    filmGrain: Boolean,
    renderQuality: FoliaRenderQuality,
    showBottomSubtitle: Boolean,
    isDaylight: Boolean,
    bgStatic: Boolean,
    fontStyle: com.walkman.tv.data.store.FoliaFontStyle,
    animIntensity: com.walkman.tv.data.store.FoliaAnimIntensity,
    fontScale: com.walkman.tv.data.store.FoliaFontScale,
    moodLabel: String,
    accent: Color,
    bright: Color,
    orb1: Color,
    orb2: Color,
): String {
    val root = JSONObject()
    root.put("template", template.id)
    root.put("background", background.id)
    root.put("beatReactive", beatReactive)
    root.put("glowEnabled", glowEnabled)
    root.put("filmGrain", filmGrain)
    root.put("renderScale", renderQuality.scale.toDouble())
    root.put("showBottomSubtitle", showBottomSubtitle)
    root.put("isDaylight", isDaylight)
    root.put("bgStatic", bgStatic)
    root.put("fontStyle", fontStyle.id)
    root.put("animIntensity", animIntensity.id)
    root.put("fontScale", fontScale.scale.toDouble())
    root.put("moodLabel", moodLabel)
    root.put("accent", accent.toHexRgb())
    root.put("bright", bright.toHexRgb())
    root.put("orb1", orb1.toHexRgb())
    root.put("orb2", orb2.toHexRgb())

    val trackObj = JSONObject()
    trackObj.put("id", track.id)
    trackObj.put("name", track.name)
    trackObj.put("singer", track.singer)
    trackObj.put("picURL", track.picURL ?: "")
    root.put("track", trackObj)

    val arr = JSONArray()
    for (line in lyrics) {
        if (line.text.isBlank()) continue
        val item = JSONObject()
        item.put("t", line.time.coerceAtLeast(0.0))
        item.put("text", line.text)
        item.put("trans", line.translation ?: "")
        arr.put(item)
    }
    root.put("lyrics", arr)
    return root.toString()
}
