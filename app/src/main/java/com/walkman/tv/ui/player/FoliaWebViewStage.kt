package com.walkman.tv.ui.player

import android.annotation.SuppressLint
import android.view.View
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
import com.walkman.tv.data.store.FoliaTemplate
import com.walkman.tv.playback.LyricLine
import com.walkman.tv.ui.appContainer
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan B experimental WebView GPU stage reproducing all 18 Folia lyric visualizer templates.
 *
 * Designed specifically for Android TV safety:
 * - Non-focusable (`isFocusable = false`) so D-pad navigation & wake-keys never get trapped.
 * - Loads 100% offline from `file:///android_asset/folia/stage.html`.
 * - Bridges playback position at ~20Hz while the WebView interpolates smoothly at 60fps.
 * - Explicitly destroys the WebView in [DisposableEffect] to reclaim GPU/RAM immediately.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FoliaWebViewStage(
    track: Track,
    lyrics: List<LyricLine>,
    template: FoliaTemplate,
    background: com.walkman.tv.data.store.FoliaBackground,
    beatReactive: Boolean,
    glowEnabled: Boolean,
    filmGrain: Boolean,
    renderQuality: com.walkman.tv.data.store.FoliaRenderQuality,
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
        wv.evaluateJavascript("window.__foliaState($stateJson);", null)
        val pos = controller.player.currentPosition.coerceAtLeast(0L)
        wv.evaluateJavascript("window.__foliaTick($pos, $isPlaying, 0);", null)
    }

    LaunchedEffect(pageReady, isPlaying, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        while (true) {
            val pos = controller.player.currentPosition.coerceAtLeast(0L)
            val level = controller.audioLevel.value.coerceIn(0f, 1f)
            wv.evaluateJavascript("window.__foliaTick($pos, $isPlaying, $level);", null)
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
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageReady = true
                    }
                }
                webViewRef = this
                loadUrl("file:///android_asset/folia/stage.html")
            }
        },
    )
}

private fun Color.toHexRgb(): String {
    val rgb = toArgb() and 0xFFFFFF
    return String.format("#%06X", rgb)
}

private fun buildStateJson(
    track: Track,
    lyrics: List<LyricLine>,
    template: FoliaTemplate,
    background: com.walkman.tv.data.store.FoliaBackground,
    beatReactive: Boolean,
    glowEnabled: Boolean,
    filmGrain: Boolean,
    renderQuality: com.walkman.tv.data.store.FoliaRenderQuality,
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
