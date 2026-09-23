package com.walkman.tv.ui.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.theme.AppColors

/** Speech recognition stays on the search screen and returns into the existing search pipeline. */
@Composable
fun VoiceSearchButton(onRecognized: (String) -> Unit) {
    val context = LocalContext.current
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在聆听…") }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(recognizer) {
        onDispose { recognizer?.destroy() }
    }

    val startRecognition: () -> Unit = {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            error = "当前设备没有可用的语音识别服务，请使用遥控器键盘或扫码输入。"
        } else {
            runCatching {
                recognizer?.destroy()
                val service = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = service
                service.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { status = "请说歌曲、歌手或歌单" }
                    override fun onBeginningOfSpeech() { status = "正在聆听…" }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { status = "正在识别…" }
                    override fun onError(code: Int) {
                        listening = false
                        error = when (code) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "没有听清，请再试一次。"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "请允许麦克风权限后再试。"
                            else -> "语音识别暂时不可用（错误 $code）。"
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        listening = false
                        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (phrase.isNotBlank()) onRecognized(phrase)
                        else error = "没有听清，请再试一次。"
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!partial.isNullOrBlank()) status = partial
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                status = "正在聆听…"
                listening = true
                service.startListening(intent)
            }.onFailure {
                listening = false
                error = "无法启动语音识别，请使用遥控器键盘或扫码输入。"
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecognition()
        else error = "请允许麦克风权限后再试。"
    }

    TvPill(
        onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) startRecognition()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        },
        shape = CircleShape,
        contentPadding = PaddingValues(10.dp),
    ) {
        Icon(Icons.Filled.Mic, contentDescription = "语音搜索", modifier = Modifier.size(22.dp))
    }

    if (listening) {
        Dialog(
            onDismissRequest = { recognizer?.cancel(); listening = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier.width(360.dp).clip(RoundedCornerShape(18.dp))
                    .background(AppColors.BgPanel).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null,
                    tint = AppColors.BrandPrimary, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(14.dp))
                Text("语音搜索", color = AppColors.TextPrimary, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(status, color = AppColors.TextSecondary, fontSize = 15.sp)
                Spacer(Modifier.height(20.dp))
                TvPill(onClick = { recognizer?.cancel(); listening = false }) {
                    Text("取消", fontSize = 14.sp)
                }
            }
        }
    }
    error?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("语音搜索") },
            text = { Text(message) },
            confirmButton = {
                TvPill(onClick = { error = null }) { Text("知道了") }
            },
            containerColor = AppColors.BgPanel,
            titleContentColor = AppColors.TextPrimary,
            textContentColor = AppColors.TextSecondary,
        )
    }
}
