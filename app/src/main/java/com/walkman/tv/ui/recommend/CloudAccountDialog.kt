package com.walkman.tv.ui.recommend

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.walkman.tv.cloud.QqAccount
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.QrDialog
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CloudAccountDialog(onDismiss: () -> Unit) {
    val netease = appContainer.neteaseAccount
    val qq = appContainer.qqAccount
    val neteaseState by netease.state.collectAsState()
    val qqState by qq.state.collectAsState()
    val active by appContainer.cloudSelection.source.collectAsState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    var neteaseQr by remember { mutableStateOf<String?>(null) }
    var qqQr by remember { mutableStateOf<QqAccount.Qr?>(null) }
    var status by remember { mutableStateOf("请扫码登录") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(neteaseQr) {
        val key = neteaseQr ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            runCatching { netease.pollQr(key) }
                .onSuccess { code ->
                    when (code) {
                        802 -> status = "已扫码，请在手机上确认"
                        803 -> {
                            appContainer.cloudSelection.select(SourceID.WY)
                            neteaseQr = null
                            onDismiss()
                            return@LaunchedEffect
                        }
                        800 -> {
                            neteaseQr = null
                            error = "二维码已过期，请重新生成"
                            return@LaunchedEffect
                        }
                    }
                }
                .onFailure {
                    neteaseQr = null
                    error = it.message ?: "网易云登录失败"
                    return@LaunchedEffect
                }
        }
    }

    LaunchedEffect(qqQr?.qrsig) {
        val qr = qqQr ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            runCatching { qq.pollQr(qr) }
                .onSuccess { code ->
                    when (code) {
                        67 -> status = "已扫码，请在手机上确认"
                        0 -> {
                            appContainer.cloudSelection.select(SourceID.TX)
                            qqQr = null
                            onDismiss()
                            return@LaunchedEffect
                        }
                        65 -> {
                            qqQr = null
                            error = "二维码已过期，请重新生成"
                            return@LaunchedEffect
                        }
                    }
                }
                .onFailure {
                    qqQr = null
                    error = it.message ?: "QQ 音乐登录失败"
                    return@LaunchedEffect
                }
        }
    }

    neteaseQr?.let { key ->
        QrDialog(netease.qrUrl(key), "网易云音乐扫码登录", status,
            onDismiss = { neteaseQr = null })
        return
    }
    qqQr?.let { qr ->
        QqQrDialog(qr, status, onDismiss = { qqQr = null })
        return
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(20.dp))
                .background(AppColors.BgPanel).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("音乐账号", color = AppColors.TextPrimary, fontSize = 22.sp,
                fontWeight = FontWeight.Bold)
            Text("连接多个账号，选择一个用于推荐页",
                color = AppColors.TextSecondary, fontSize = 13.sp)
            Text("网易云音乐", color = AppColors.TextPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth().height(104.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(neteaseState.accounts) { account ->
                    AccountRow(account.nickname,
                        active == SourceID.WY && account.id == neteaseState.activeId) {
                        netease.select(account.id)
                        appContainer.cloudSelection.select(SourceID.WY)
                        onDismiss()
                    }
                }
            }
            AccountRow("＋ 添加网易云账号", false, firstFocus) {
                if (!busy) scope.launch {
                    busy = true
                    error = null
                    runCatching { netease.newQr() }
                        .onSuccess { neteaseQr = it; status = "请用网易云音乐扫码" }
                        .onFailure { error = it.message ?: "无法生成二维码" }
                    busy = false
                }
            }
            Text("QQ 音乐", color = AppColors.TextPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth().height(104.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(qqState.accounts) { account ->
                    AccountRow(account.name,
                        active == SourceID.TX && account.id == qqState.activeId) {
                        qq.select(account.id)
                        appContainer.cloudSelection.select(SourceID.TX)
                        onDismiss()
                    }
                }
            }
            AccountRow("＋ 添加 QQ 音乐账号", false) {
                if (!busy) scope.launch {
                    busy = true
                    error = null
                    runCatching { qq.newQr() }
                        .onSuccess { qqQr = it; status = "请用 QQ 扫码" }
                        .onFailure { error = it.message ?: "无法生成二维码" }
                    busy = false
                }
            }
            if (busy) Text("正在生成二维码…", color = AppColors.TextSecondary, fontSize = 12.sp)
            error?.let { Text(it, color = AppColors.BrandPrimary, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val hasActive = if (active == SourceID.TX) qqState.connected else neteaseState.connected
                if (hasActive) {
                    TvPill(onClick = {
                        if (active == SourceID.TX) qq.disconnect() else netease.disconnect()
                    }) { Text("移除当前账号", fontSize = 13.sp) }
                    Spacer(Modifier.width(10.dp))
                }
                TvPill(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun AccountRow(title: String, selected: Boolean,
    focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    TvFocusable(onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(42.dp)
            .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)),
        shape = RoundedCornerShape(11.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), color = AppColors.TextPrimary,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Text("使用中", color = AppColors.BrandPrimary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun QqQrDialog(qr: QqAccount.Qr, status: String, onDismiss: () -> Unit) {
    val bitmap = remember(qr.qrsig) {
        BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)?.asImageBitmap()
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(360.dp).clip(RoundedCornerShape(18.dp))
            .background(AppColors.BgPanel).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("QQ 音乐扫码登录", color = AppColors.TextPrimary,
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(status, color = AppColors.TextSecondary, fontSize = 13.sp)
            if (bitmap != null) {
                Box(Modifier.size(220.dp).background(androidx.compose.ui.graphics.Color.White)
                    .padding(6.dp)) {
                    Image(bitmap, contentDescription = "QQ 登录二维码",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            } else Text("二维码图像无效", color = AppColors.TextSecondary)
            TvPill(onClick = onDismiss) { Text("取消", fontSize = 14.sp) }
        }
    }
}
