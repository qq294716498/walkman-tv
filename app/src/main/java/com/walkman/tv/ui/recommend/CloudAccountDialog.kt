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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.walkman.tv.cloud.QishuiAccount
import com.walkman.tv.cloud.QqAccount
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.QrDialog
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ACCOUNT_TABS = listOf(SourceID.QS, SourceID.WY, SourceID.TX)

@Composable
fun CloudAccountDialog(onDismiss: () -> Unit) {
    val qishui = appContainer.qishuiAccount
    val netease = appContainer.neteaseAccount
    val qq = appContainer.qqAccount
    val qishuiState by qishui.state.collectAsState()
    val neteaseState by netease.state.collectAsState()
    val qqState by qq.state.collectAsState()
    val active by appContainer.cloudSelection.source.collectAsState()
    val scope = rememberCoroutineScope()

    val qsFocus = remember { FocusRequester() }
    val wyFocus = remember { FocusRequester() }
    val txFocus = remember { FocusRequester() }
    var tab by remember {
        mutableStateOf(if (active in ACCOUNT_TABS) active else SourceID.QS)
    }
    LaunchedEffect(tab) {
        runCatching {
            when (tab) {
                SourceID.QS -> qsFocus.requestFocus()
                SourceID.WY -> wyFocus.requestFocus()
                SourceID.TX -> txFocus.requestFocus()
                else -> qsFocus.requestFocus()
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { qq.refreshNames() } }

    var qishuiQr by remember { mutableStateOf<QishuiAccount.Qr?>(null) }
    var neteaseQr by remember { mutableStateOf<String?>(null) }
    var qqQr by remember { mutableStateOf<QqAccount.Qr?>(null) }
    var showSms by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("请扫码登录") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(qishuiQr?.token) {
        val qr = qishuiQr ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            runCatching { qishui.pollQr(qr) }
                .onSuccess { st ->
                    when (st) {
                        QishuiAccount.QrStatus.WAITING -> Unit
                        QishuiAccount.QrStatus.SCANNED -> status = "已扫码，请在手机上确认登录"
                        QishuiAccount.QrStatus.CONFIRMED -> {
                            appContainer.cloudSelection.select(SourceID.QS)
                            qishuiQr = null
                            onDismiss()
                            return@LaunchedEffect
                        }
                        QishuiAccount.QrStatus.EXPIRED -> {
                            qishuiQr = null
                            error = "二维码已过期，请重新生成"
                            return@LaunchedEffect
                        }
                        QishuiAccount.QrStatus.REFUSED -> {
                            qishuiQr = null
                            error = "已在手机上取消授权"
                            return@LaunchedEffect
                        }
                    }
                }
                .onFailure {
                    qishuiQr = null
                    error = it.message ?: "汽水音乐登录失败"
                    return@LaunchedEffect
                }
        }
    }

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

    qishuiQr?.let { qr ->
        BinaryQrDialog(
            title = "汽水音乐扫码登录",
            imageBytes = qr.image,
            cacheKey = qr.token,
            status = status,
            onDismiss = { qishuiQr = null },
        )
        return
    }
    neteaseQr?.let { key ->
        QrDialog(
            netease.qrUrl(key),
            "网易云音乐扫码登录",
            status,
            onDismiss = { neteaseQr = null },
        )
        return
    }
    qqQr?.let { qr ->
        BinaryQrDialog(
            title = "QQ 音乐扫码登录",
            imageBytes = qr.image,
            cacheKey = qr.qrsig,
            status = status,
            onDismiss = { qqQr = null },
        )
        return
    }

    if (showSms) {
        NeteaseSmsLoginDialog(onDismiss = { showSms = false }, onConnected = {
            appContainer.cloudSelection.select(SourceID.WY)
            showSms = false
            onDismiss()
        })
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AppColors.BgPanel)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "音乐账号",
                color = AppColors.TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "按 汽水 → 网易云 → QQ 管理已登录账号；登录后可同步歌单与个性电台",
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ACCOUNT_TABS.forEachIndexed { idx, source ->
                    val selected = tab == source
                    val title = when (source) {
                        SourceID.QS -> "汽水"
                        SourceID.WY -> "网易云"
                        SourceID.TX -> "QQ音乐"
                        else -> source.displayName
                    }
                    val count = when (source) {
                        SourceID.QS -> qishuiState.accounts.size
                        SourceID.WY -> neteaseState.accounts.size
                        SourceID.TX -> qqState.accounts.size
                        else -> 0
                    }
                    val fr = when (source) {
                        SourceID.QS -> qsFocus
                        SourceID.WY -> wyFocus
                        SourceID.TX -> txFocus
                        else -> qsFocus
                    }
                    TvFocusable(
                        onClick = { tab = source; error = null },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .focusRequester(fr)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            if (idx > 0) {
                                                tab = ACCOUNT_TABS[idx - 1]
                                                error = null
                                            }
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            if (idx + 1 < ACCOUNT_TABS.size) {
                                                tab = ACCOUNT_TABS[idx + 1]
                                                error = null
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            },
                        shape = RoundedCornerShape(12.dp),
                        container = if (selected) AppColors.Card else AppColors.BgPanel,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "$title · $count",
                                color = if (selected) AppColors.BrandPrimary else AppColors.TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Text(
                "已登录账号",
                color = AppColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            val count = when (tab) {
                SourceID.QS -> qishuiState.accounts.size
                SourceID.WY -> neteaseState.accounts.size
                SourceID.TX -> qqState.accounts.size
                else -> 0
            }
            if (count == 0) {
                Text(
                    "尚未登录",
                    color = AppColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    when (tab) {
                        SourceID.QS -> {
                            items(qishuiState.accounts, key = { it.id }) { account ->
                                val label = buildString {
                                    append(account.nickname.ifBlank { "汽水用户 ${account.id}" })
                                    if (account.isVip) append(" · VIP")
                                }
                                AccountRow(label, qishuiState.activeId == account.id) {
                                    qishui.select(account.id)
                                    appContainer.cloudSelection.select(SourceID.QS)
                                }
                            }
                        }
                        SourceID.WY -> {
                            items(neteaseState.accounts, key = { it.id }) { account ->
                                AccountRow(
                                    account.nickname.ifBlank { account.id },
                                    neteaseState.activeId == account.id,
                                ) {
                                    netease.select(account.id)
                                    appContainer.cloudSelection.select(SourceID.WY)
                                }
                            }
                        }
                        else -> {
                            items(qqState.accounts, key = { it.id }) { account ->
                                AccountRow(
                                    account.name.ifBlank { "QQ ${account.id}" },
                                    qqState.activeId == account.id,
                                ) {
                                    qq.select(account.id)
                                    appContainer.cloudSelection.select(SourceID.TX)
                                }
                            }
                        }
                    }
                }
            }
            Text(
                "添加账号",
                color = AppColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            when (tab) {
                SourceID.QS -> {
                    AccountRow("扫码登录汽水音乐（抖音 / 汽水 App 扫码）", false) {
                        if (!busy) scope.launch {
                            busy = true
                            error = null
                            runCatching { qishui.newQr() }
                                .onSuccess { qishuiQr = it; status = "请用抖音或汽水音乐扫码" }
                                .onFailure { error = it.message ?: "无法生成汽水二维码" }
                            busy = false
                        }
                    }
                }
                SourceID.WY -> {
                    AccountRow("扫码登录网易云", false) {
                        if (!busy) scope.launch {
                            busy = true
                            error = null
                            runCatching { netease.newQr() }
                                .onSuccess { neteaseQr = it; status = "请用网易云音乐扫码" }
                                .onFailure { error = it.message ?: "无法生成二维码" }
                            busy = false
                        }
                    }
                    AccountRow("手机号验证码登录", false) { showSms = true }
                }
                else -> {
                    AccountRow("扫码登录 QQ 音乐", false) {
                        if (!busy) scope.launch {
                            busy = true
                            error = null
                            runCatching { qq.newQr() }
                                .onSuccess { qqQr = it; status = "请用 QQ 扫码" }
                                .onFailure { error = it.message ?: "无法生成二维码" }
                            busy = false
                        }
                    }
                }
            }
            if (busy) Text("正在生成二维码…", color = AppColors.TextSecondary, fontSize = 12.sp)
            error?.let { Text(it, color = AppColors.BrandPrimary, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val hasActive = when (tab) {
                    SourceID.QS -> qishuiState.connected
                    SourceID.WY -> neteaseState.connected
                    SourceID.TX -> qqState.connected
                    else -> false
                }
                if (hasActive) {
                    TvPill(onClick = {
                        when (tab) {
                            SourceID.QS -> qishui.disconnect()
                            SourceID.WY -> netease.disconnect()
                            SourceID.TX -> qq.disconnect()
                            else -> Unit
                        }
                    }) { Text("移除选中账号", fontSize = 13.sp) }
                    Spacer(Modifier.width(10.dp))
                }
                TvPill(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun AccountRow(
    title: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    TvFocusable(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)),
        shape = RoundedCornerShape(11.dp),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = AppColors.TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) Text("使用中", color = AppColors.BrandPrimary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BinaryQrDialog(
    title: String,
    imageBytes: ByteArray,
    cacheKey: String,
    status: String,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(cacheKey) {
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.asImageBitmap()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AppColors.BgPanel)
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = AppColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(status, color = AppColors.TextSecondary, fontSize = 13.sp)
            if (bitmap != null) {
                Box(
                    Modifier
                        .size(220.dp)
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(6.dp),
                ) {
                    Image(
                        bitmap,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                Text("二维码图像无效", color = AppColors.TextSecondary)
            }
            TvPill(onClick = onDismiss) { Text("取消", fontSize = 14.sp) }
        }
    }
}

@Composable
private fun NeteaseSmsLoginDialog(onDismiss: () -> Unit, onConnected: () -> Unit) {
    val account = appContainer.neteaseAccount
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val phoneFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { phoneFocus.requestFocus() } }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            countdown -= 1
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AppColors.BgPanel)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "网易云手机验证码登录",
                color = AppColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "目前支持中国大陆 +86 手机号",
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                label = { Text("手机号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.TextPrimary,
                    unfocusedTextColor = AppColors.TextPrimary,
                    focusedLabelColor = AppColors.BrandPrimary,
                    unfocusedLabelColor = AppColors.TextSecondary,
                    focusedBorderColor = AppColors.BrandPrimary,
                    unfocusedBorderColor = AppColors.TextMuted,
                    cursorColor = AppColors.BrandPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(phoneFocus),
            )
            TvPill(onClick = {
                if (!busy && countdown == 0) scope.launch {
                    busy = true
                    message = null
                    runCatching { account.sendCode(phone) }
                        .onSuccess {
                            countdown = 60
                            message = "验证码已发送"
                        }
                        .onFailure { message = it.message ?: "发送失败" }
                    busy = false
                }
            }) {
                Text(
                    if (countdown > 0) "重新发送 ${countdown}s" else "发送验证码",
                    fontSize = 13.sp,
                )
            }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(8) },
                label = { Text("输入验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.TextPrimary,
                    unfocusedTextColor = AppColors.TextPrimary,
                    focusedLabelColor = AppColors.BrandPrimary,
                    unfocusedLabelColor = AppColors.TextSecondary,
                    focusedBorderColor = AppColors.BrandPrimary,
                    unfocusedBorderColor = AppColors.TextMuted,
                    cursorColor = AppColors.BrandPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let { Text(it, color = AppColors.BrandPrimary, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvPill(
                    onClick = {
                        if (!busy) scope.launch {
                            busy = true
                            message = null
                            runCatching { account.loginWithCode(phone, code) }
                                .onSuccess { onConnected() }
                                .onFailure { message = it.message ?: "登录失败" }
                            busy = false
                        }
                    },
                    selected = true,
                ) { Text(if (busy) "处理中…" else "登录", fontSize = 13.sp) }
                TvPill(onClick = onDismiss) { Text("返回", fontSize = 13.sp) }
            }
        }
    }
}
