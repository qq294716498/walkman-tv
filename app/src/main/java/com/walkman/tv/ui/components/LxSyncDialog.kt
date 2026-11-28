package com.walkman.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.walkman.tv.cloud.LxSyncClient
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * TV dialog for configuring and syncing with a self-hosted `lx-music-sync-server`.
 * Supports both direct TV input and scanning a LAN QR code to type the URL + password on a phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LxSyncDialog(onDismiss: () -> Unit) {
    val syncClient = appContainer.lxSyncClient
    val state by syncClient.state.collectAsState()
    val scope = rememberCoroutineScope()

    var serverUrl by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var authCode by remember(state.authCode) { mutableStateOf(state.authCode) }
    var syncMode by remember(state.syncMode) { mutableStateOf(state.syncMode) }
    var showQr by remember { mutableStateOf(false) }

    val urlFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { urlFocus.requestFocus() } }

    LaunchedEffect(Unit) {
        appContainer.events.qrLxSyncConfig.collect { payload ->
            showQr = false
            serverUrl = payload.serverUrl
            if (payload.authCode.isNotBlank()) authCode = payload.authCode
            syncClient.connectAndSync(payload.serverUrl, payload.authCode.ifBlank { authCode }, syncMode)
        }
    }

    if (showQr) {
        val port = appContainer.localServer?.boundPort
        val ip = remember { com.walkman.tv.di.getLanIp() }
        val qrUrl = if (port != null && ip != null) "http://$ip:$port/lx-sync" else null
        if (qrUrl != null) {
            QrDialog(
                url = qrUrl,
                title = "手机扫码填写 LX 同步服务器",
                subtitle = "手机与电视在同一 Wi-Fi 下扫码，输入服务器地址和同步密码即可自动同步",
                onDismiss = { showQr = false },
            )
            return
        } else {
            showQr = false
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = AppColors.TextPrimary,
        unfocusedTextColor = AppColors.TextPrimary,
        focusedLabelColor = AppColors.BrandPrimary,
        unfocusedLabelColor = AppColors.TextSecondary,
        focusedBorderColor = AppColors.BrandPrimary,
        unfocusedBorderColor = AppColors.TextMuted,
        cursorColor = AppColors.BrandPrimary,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AppColors.BgPanel)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "落雪歌单同步（LX Sync Server）",
                        color = AppColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "连接你自建的 lx-music-sync-server，同步「我喜欢的」与全部自建歌单到音乐库",
                        color = AppColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                TvPill(onClick = { showQr = true }) {
                    Text("手机扫码填写", fontSize = 12.sp)
                }
            }

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("服务器地址（如 http://192.168.1.10:9527 或 https://域名）") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(urlFocus),
            )

            OutlinedTextField(
                value = authCode,
                onValueChange = { authCode = it },
                label = { Text("同步密码（首次连接必填，已连接可留空）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("同步策略", color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LxSyncClient.SyncMode.entries.forEach { mode ->
                        TvPill(
                            onClick = { syncMode = mode },
                            selected = syncMode == mode,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(mode.label, fontSize = 12.sp)
                        }
                    }
                }
                Text(syncMode.desc, color = AppColors.TextMuted, fontSize = 11.sp)
            }

            if (state.statusMessage.isNotBlank()) {
                Text(
                    buildString {
                        if (state.serverName.isNotBlank()) append("[${state.serverName}] ")
                        append(state.statusMessage)
                    },
                    color = AppColors.BrandPrimary,
                    fontSize = 12.sp,
                )
            }
            state.error?.let { err ->
                Text("同步失败：$err", color = AppColors.Danger, fontSize = 12.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (state.connected) {
                    TvPill(onClick = {
                        syncClient.disconnect()
                        serverUrl = ""
                        authCode = ""
                    }) { Text("断开并清除", fontSize = 13.sp) }
                    Spacer(Modifier.width(10.dp))
                }
                TvPill(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                Spacer(Modifier.width(10.dp))
                TvPill(
                    onClick = {
                        if (!state.syncing) {
                            scope.launch {
                                syncClient.connectAndSync(serverUrl, authCode, syncMode)
                            }
                        }
                    },
                    selected = true,
                ) {
                    Text(if (state.syncing) "同步中…" else "连接并同步", fontSize = 13.sp)
                }
            }
        }
    }
}
