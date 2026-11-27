package com.walkman.tv.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.store.FoliaBackground
import com.walkman.tv.data.store.FoliaRenderQuality
import com.walkman.tv.data.store.FoliaTemplate
import com.walkman.tv.playback.LyricLine
import com.walkman.tv.ui.appContainer
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private const val FOLIA_ORIGIN_HOST = "folia.local"
private const val FOLIA_ENTRY_URL = "https://$FOLIA_ORIGIN_HOST/tv-stage.html"

/**
 * Plan B WebView GPU stage hosting the genuine `whycfawa/folia-android` visualizer engine
 * (PixiJS v8 + Three.js + GLSL shaders + all 18 official templates & 6 backgrounds).
 *
 * Served offline from `assets/folia/` via a virtual HTTPS origin (`https://folia.local/`) so
 * ES modules, dynamic chunk imports, WebGL2 shaders, and fetch() work identically to a real server.
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
    moodLabel: String,
    showTranslation: Boolean,
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
        moodLabel,
        showTranslation,
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
            moodLabel = moodLabel,
            showTranslation = showTranslation,
            accent = accentColor,
            bright = brightColor,
            orb1 = if (orbPrimary.alpha > 0.05f) orbPrimary else Color(0xFF1E3A8A),
            orb2 = if (orbSecondary.alpha > 0.05f) orbSecondary else Color(0xFF312E81),
        )
    }

    LaunchedEffect(pageReady, stateJson, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        wv.evaluateJavascript(
            "if(window.__foliaState){window.__foliaState($stateJson);}else{window.__foliaPendingState=$stateJson;}",
            null,
        )
        val pos = controller.player.currentPosition.coerceAtLeast(0L)
        wv.evaluateJavascript(
            "if(window.__foliaTick){window.__foliaTick($pos, $isPlaying, 0);}",
            null,
        )
    }

    LaunchedEffect(pageReady, isPlaying, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
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
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webViewRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setBackgroundColor(android.graphics.Color.BLACK)
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                isLongClickable = false
                overScrollMode = View.OVER_SCROLL_NEVER
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val uri = request?.url ?: return null
                        if (uri.host != FOLIA_ORIGIN_HOST) return null
                        return serveFoliaAsset(ctx, uri.path ?: "/tv-stage.html")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageReady = true
                    }
                }
                webViewRef = this
                loadUrl(FOLIA_ENTRY_URL)
            }
        },
    )
}

private fun serveFoliaAsset(context: Context, rawPath: String): WebResourceResponse {
    val clean = rawPath.trimStart('/').ifEmpty { "tv-stage.html" }
    val assetPath = "folia/$clean"
    val headers = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "public, max-age=31536000, immutable",
    )
    return try {
        val stream = context.assets.open(assetPath)
        val mime = guessMimeType(clean)
        WebResourceResponse(mime, "UTF-8", 200, "OK", headers, stream)
    } catch (_: Throwable) {
        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", headers, null)
    }
}

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
    moodLabel: String,
    showTranslation: Boolean,
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
    root.put("moodLabel", moodLabel)
    root.put("showTrans", showTranslation)
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
