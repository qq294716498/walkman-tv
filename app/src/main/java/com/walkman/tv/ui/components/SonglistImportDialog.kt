package com.walkman.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.playback.imports.SonglistImporter
import com.walkman.tv.playback.imports.SonglistRef
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Online songlist (歌单) importer. Spec docs/songlist-import-spec-android-tv.md.
 *
 * Flow:
 *   1. User pastes a share URL (or scans the QR to push from phone). Every keystroke runs
 *      [SonglistImporter.parse] / [SonglistImporter.pureIdOrNull] so the recognition status
 *      flips between "✓ 已识别" / source picker / nothing in real time.
 *   2. Optional 歌单名 override — same control as PlaylistNameDialog (with QR fallback).
 *   3. 确定导入 → [SonglistImporter.importPlaylist] off the UI thread. 900ms confirmation, then
 *      dismiss. Errors render inline as red text so the user can correct and retry.
 *
 * BACK closes the dialog *except* while importing — partial state would orphan a half-filled
 * playlist.
 */
@Composable
fun SonglistImportDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val libraryStore = appContainer.libraryStore
    var raw by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var manualSource by remember { mutableStateOf<SourceID?>(null) }
    var importing by remember { mutableStateOf(false) }
    var doneMessage by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }

    val parsedRef = remember(raw) { SonglistImporter.parse(raw) }
    val pureId = remember(raw, parsedRef) {
        if (parsedRef == null) SonglistImporter.pureIdOrNull(raw) else null
    }
    val canImport = !importing && doneMessage == null && (
        parsedRef != null || (pureId != null && manualSource != null)
        )

    fun runLxBytesImport(bytes: ByteArray) {
        if (importing) return
        importing = true
        errorText = null
        showQr = false
        scope.launch {
            com.walkman.tv.playback.imports.LxPlaylistImporter
                .importBytes(bytes, libraryStore)
                .onSuccess { summary ->
                    val lovePart = if (summary.loveCount > 0) "（含我喜欢的 ${summary.loveCount} 首）" else ""
                    doneMessage = "✓ 已导入 ${summary.playlistCount} 个 LX 歌单，共 ${summary.trackCount} 首$lovePart"
                    importing = false
                    delay(1200)
                    onDismiss()
                }
                .onFailure { e ->
                    errorText = "LX 歌单导入失败：${e.localizedMessage ?: e}"
                    importing = false
                }
        }
    }

    val lxFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes != null && bytes.isNotEmpty()) {
                runLxBytesImport(bytes)
            } else {
                errorText = "无法读取所选文件"
            }
        }
    }

    // QR-pushed URL replaces whatever's in the field.
    LaunchedEffect(Unit) {
        appContainer.events.qrSonglistUrl.collect { received ->
            raw = received.trim()
            showQr = false
        }
    }
    // QR-pushed LX playlist file (.lxmc / .json) imports immediately.
    LaunchedEffect(Unit) {
        appContainer.events.qrLxPlaylistBytes.collect { bytes ->
            runLxBytesImport(bytes)
        }
    }

    Dialog(
        onDismissRequest = { if (!importing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Read inside Dialog: it's a separate window with its own focus owner.
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text("导入歌单", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(4.dp))
            Text(
                "支持酷我 / 酷狗 / QQ 音乐 / 网易云歌单链接，或落雪音乐 (LX) 导出的 .lxmc / .json 歌单文件。",
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.size(14.dp))

            Text("歌单链接", color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            TvTextFieldHost(modifier = Modifier.fillMaxWidth()) { fieldModifier ->
                BasicTextField(
                    value = raw,
                    onValueChange = {
                        raw = it
                        errorText = null
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = AppColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(AppColors.AccentGreen),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    // Done: keep the dialog open (no submit) but step off the field so the remote can reach 导入.
                    keyboardActions = KeyboardActions(onDone = { leaveField(focusManager, keyboard) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(fieldModifier)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.BgDeep)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (raw.isEmpty()) {
                                Text(
                                    "粘贴链接或纯数字 ID（手机扫码更省事）",
                                    color = AppColors.TextMuted,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.size(6.dp))
            when {
                parsedRef != null -> Text(
                    "✓ 已识别：${parsedRef.source.displayName}歌单 · ${parsedRef.id}",
                    color = AppColors.AccentGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                pureId != null -> {
                    Text("识别为纯 ID，请选择来源平台：", color = AppColors.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.size(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(SourceID.WY, SourceID.TX, SourceID.KG, SourceID.KW).forEach { s ->
                            TvPill(
                                onClick = { manualSource = s },
                                selected = manualSource == s,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(s.displayName, fontSize = 12.sp)
                            }
                        }
                    }
                }
                raw.isNotBlank() -> Text(
                    "未识别 — 请粘贴酷我 / 酷狗 / QQ / 网易云的歌单分享链接",
                    color = AppColors.TextMuted,
                    fontSize = 12.sp,
                )
                else -> Spacer(Modifier.size(0.dp))
            }

            Spacer(Modifier.size(14.dp))
            Text("歌单名（可选）", color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            TvPill(
                onClick = { showNameDialog = true },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    if (customName.isBlank()) "（留空则用原歌单名）" else customName,
                    fontSize = 13.sp,
                    color = if (customName.isBlank()) AppColors.TextMuted else AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (importing) {
                Spacer(Modifier.size(12.dp))
                Text(
                    "正在拉取曲目…",
                    color = AppColors.AccentGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            doneMessage?.let {
                Spacer(Modifier.size(12.dp))
                Text(
                    it,
                    color = AppColors.AccentGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            errorText?.let {
                Spacer(Modifier.size(10.dp))
                Text(it, color = AppColors.Danger, fontSize = 12.sp)
            }

            Spacer(Modifier.size(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvPill(
                    onClick = { showQr = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("手机扫码/传LX", fontSize = 12.sp)
                }
                TvPill(
                    onClick = {
                        runCatching { lxFileLauncher.launch(arrayOf("*/*")) }
                            .onFailure { errorText = "电视缺少文件选择器，请使用「手机扫码/传LX」上传" }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("本地 LX 文件", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                TvPill(
                    onClick = { if (!importing) onDismiss() },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(if (doneMessage != null) "完成" else "取消", fontSize = 13.sp)
                }
                if (doneMessage == null) {
                    val startImport: () -> Unit = startImport@{
                        if (!canImport) return@startImport
                        val ref = parsedRef ?: pureId?.let { id ->
                            manualSource?.let { s -> SonglistRef(s, id) }
                        } ?: return@startImport
                        importing = true
                        errorText = null
                        scope.launch {
                            runCatching {
                                SonglistImporter.importPlaylist(
                                    ref = ref,
                                    customName = customName.takeIf { it.isNotBlank() },
                                    songlists = appContainer.songlists,
                                    library = appContainer.libraryStore,
                                )
                            }.onSuccess { result ->
                                doneMessage = "✓ 导入完成，共 ${result.count} 首"
                                importing = false
                                // 900ms grace so user sees the confirmation before dismiss.
                                delay(900)
                                onDismiss()
                            }.onFailure { e ->
                                errorText = e.localizedMessage ?: e.toString()
                                importing = false
                            }
                        }
                    }
                    TvPill(
                        onClick = startImport,
                        selected = canImport,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(
                            if (importing) "导入中…" else "确定导入",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    if (showQr) {
        val ip = remember { com.walkman.tv.di.getLanIp() }
        val port = appContainer.localServer?.boundPort
        if (ip != null && port != null) {
            QrDialog(
                url = "http://$ip:$port/songlist-url",
                title = "扫码导入歌单 / LX 文件",
                subtitle = "手机扫码可粘贴在线歌单链接，或直接上传落雪音乐 .lxmc / .json 歌单文件",
                onDismiss = { showQr = false },
            )
        } else {
            LaunchedEffect(Unit) { showQr = false }
        }
    }

    if (showNameDialog) {
        PlaylistNameDialog(
            initial = customName,
            title = "歌单名",
            onDismiss = { showNameDialog = false },
            onConfirm = { value ->
                customName = value
                showNameDialog = false
            },
        )
    }
}
