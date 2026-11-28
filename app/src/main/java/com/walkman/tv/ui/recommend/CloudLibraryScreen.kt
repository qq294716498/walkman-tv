package com.walkman.tv.ui.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.walkman.tv.cloud.QishuiAccount
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.TracksDetailOverlay
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.components.tint
import com.walkman.tv.ui.playList
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class CloudDetailView(val title: String, val subtitle: String?, val tracks: List<Track>)
private data class CloudAccountOption(val source: SourceID, val id: String, val name: String)
private data class CloudPlaylistEntry(
    val account: CloudAccountOption,
    val info: SonglistInfo,
    val isFavorite: Boolean = false,
)

private enum class CloudFilter { All, Qishui, Netease, NeteaseFavorites, Qq }

private fun SourceID.cloudShortLabel(): String = when (this) {
    SourceID.QS -> "汽水"
    SourceID.WY -> "网易云"
    SourceID.TX -> "QQ"
    else -> displayName
}

private fun SourceID.cloudFullLabel(): String = when (this) {
    SourceID.QS -> "汽水音乐"
    SourceID.WY -> "网易云"
    SourceID.TX -> "QQ音乐"
    else -> displayName
}

/**
 * Dedicated top-level screen ("登录歌单") hosting Qishui Music, NetEase Cloud Music, and QQ Music
 * features in priority order: **汽水 → 网易云 → QQ**.
 *
 * Under Qishui: **电台优先，歌单其次** — radio defaults to 默认模式 and expands on demand to show
 * all scene/mood modes (开车、工作、运动、助眠等).
 */
@Composable
fun CloudLibraryScreen(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<CloudDetailView?>(null) }
    var loadingDetail by remember { mutableStateOf(false) }
    var showAccountPreview by remember { mutableStateOf(false) }
    var showLxSync by remember { mutableStateOf(false) }

    val qishui by appContainer.qishuiAccount.state.collectAsState()
    val netease by appContainer.neteaseAccount.state.collectAsState()
    val qq by appContainer.qqAccount.state.collectAsState()

    // Strict sub-ordering priority: 汽水 → 网易云 → QQ
    val accounts = remember(qishui.accounts, netease.accounts, qq.accounts) {
        qishui.accounts.map { CloudAccountOption(SourceID.QS, it.id, it.nickname) } +
            netease.accounts.map { CloudAccountOption(SourceID.WY, it.id, it.nickname) } +
            qq.accounts.map { CloudAccountOption(SourceID.TX, it.id, it.name) }
    }

    // Qishui Radio state (default mode collapsed by default; expands to scene/mood modes)
    var activeRadioMode by remember { mutableStateOf(QishuiAccount.DEFAULT_FEED_MODE) }
    var radioExpanded by remember { mutableStateOf(false) }
    var radioModes by remember { mutableStateOf<List<QishuiAccount.FeedMode>>(emptyList()) }
    var radioModesLoading by remember { mutableStateOf(false) }
    var radioModesLoaded by remember { mutableStateOf(false) }
    var radioModesError by remember { mutableStateOf<String?>(null) }
    var radioModesRetryKey by remember { mutableStateOf(0) }
    var radioPlayingBusy by remember { mutableStateOf(false) }

    LaunchedEffect(radioExpanded, radioModesRetryKey) {
        if (radioExpanded && !radioModesLoaded && !radioModesLoading) {
            radioModesLoading = true
            radioModesError = null
            runCatching { appContainer.qishuiAccount.fetchFeedModes() }
                .onSuccess {
                    radioModes = it
                    radioModesLoaded = true
                }
                .onFailure {
                    radioModesError = it.message ?: "无法加载场景电台"
                }
            radioModesLoading = false
        }
    }

    var accountError by remember { mutableStateOf<String?>(null) }
    var cloudEntries by remember { mutableStateOf<List<CloudPlaylistEntry>>(emptyList()) }
    var cloudLoading by remember { mutableStateOf(false) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    var cloudRefresh by remember { mutableStateOf(0) }
    var loadedCloudKey by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var showAllCloud by remember { mutableStateOf(false) }
    var cloudFilter by remember { mutableStateOf(CloudFilter.All) }
    var selectedAccount by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    var lastOpenedCardKey by remember { mutableStateOf<String?>(null) }
    val lastOpenedCardFocus = remember { FocusRequester() }
    LaunchedEffect(detail) {
        if (detail == null && lastOpenedCardKey != null) {
            delay(60)
            runCatching { lastOpenedCardFocus.requestFocus() }
        }
    }

    val scopedAccounts = remember(accounts, cloudFilter) {
        accounts.filter {
            when (cloudFilter) {
                CloudFilter.All -> true
                CloudFilter.Qishui -> it.source == SourceID.QS
                CloudFilter.Netease, CloudFilter.NeteaseFavorites -> it.source == SourceID.WY
                CloudFilter.Qq -> it.source == SourceID.TX
            }
        }
    }
    val filteredEntries = remember(cloudEntries, selectedAccount, cloudFilter) {
        cloudEntries.filter { entry ->
            (selectedAccount == null || selectedAccount == "${entry.account.source}:${entry.account.id}") &&
                when (cloudFilter) {
                    CloudFilter.All -> true
                    CloudFilter.Qishui -> entry.account.source == SourceID.QS
                    CloudFilter.Netease -> entry.account.source == SourceID.WY
                    CloudFilter.NeteaseFavorites -> entry.account.source == SourceID.WY && entry.isFavorite
                    CloudFilter.Qq -> entry.account.source == SourceID.TX
                }
        }
    }

    LaunchedEffect(qishui.accounts, netease.accounts, qq.accounts, cloudRefresh) {
        val loadKey = accounts.map { "${it.source}:${it.id}:${it.name}" } to cloudRefresh
        if (loadedCloudKey == loadKey) return@LaunchedEffect
        cloudLoading = true
        cloudError = null
        cloudEntries = emptyList()
        showAllCloud = false
        val loaded = mutableListOf<CloudPlaylistEntry>()
        val failures = mutableListOf<String>()
        for (account in accounts) {
            when (account.source) {
                SourceID.QS -> {
                    runCatching { appContainer.qishuiAccount.playlists(account.id) }
                        .onSuccess { lists -> loaded += lists.map { CloudPlaylistEntry(account, it) } }
                        .onFailure { failures += "汽水 · ${account.name}: ${it.message ?: "读取失败"}" }
                }
                SourceID.WY -> {
                    runCatching { appContainer.neteaseAccount.playlistsWithKinds(account.id) }
                        .onSuccess { lists -> loaded += lists.map { CloudPlaylistEntry(account, it.info, it.subscribed) } }
                        .onFailure { failures += "网易云 · ${account.name}: ${it.message ?: "读取失败"}" }
                }
                SourceID.TX -> {
                    runCatching { appContainer.qqAccount.playlists(account.id) }
                        .onSuccess { lists -> loaded += lists.map { CloudPlaylistEntry(account, it) } }
                        .onFailure { failures += "QQ · ${account.name}: ${it.message ?: "读取失败"}" }
                }
                else -> Unit
            }
        }
        cloudEntries = loaded
        cloudError = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
        cloudLoading = false
        loadedCloudKey = loadKey
    }

    var recommended by remember { mutableStateOf<List<SonglistInfo>>(emptyList()) }
    var recommendLoading by remember { mutableStateOf(false) }
    var recommendError by remember { mutableStateOf<String?>(null) }
    var recommendRefresh by remember { mutableStateOf(0) }
    var loadedRecommendKey by remember { mutableStateOf<Pair<String?, Int>?>(null) }
    LaunchedEffect(netease.activeId, recommendRefresh) {
        val loadKey = netease.activeId to recommendRefresh
        if (loadedRecommendKey == loadKey) return@LaunchedEffect
        recommended = emptyList()
        recommendError = null
        if (!netease.connected) return@LaunchedEffect
        recommendLoading = true
        runCatching { appContainer.neteaseAccount.recommendedPlaylists() }
            .onSuccess { recommended = it }
            .onFailure { recommendError = it.message ?: "推荐歌单暂时无法加载" }
        recommendLoading = false
        loadedRecommendKey = loadKey
    }

    fun launchQishuiRadio(mode: QishuiAccount.FeedMode, openListOnly: Boolean) {
        if (radioPlayingBusy || loadingDetail) return
        activeRadioMode = mode
        scope.launch {
            radioPlayingBusy = true
            runCatching {
                val tracks = appContainer.qishuiAccount.fetchFeedSongs(mode = mode, first = false, count = 20)
                if (tracks.isEmpty()) throw IllegalStateException("汽水电台暂时没有返回歌曲，请稍后重试")
                val title = "汽水电台 · ${mode.name}"
                if (openListOnly) {
                    val sub = buildString {
                        append("汽水音乐")
                        if (qishui.connected) append(" · ${qishui.nickname}")
                        if (mode.group.isNotBlank()) append(" · ${mode.group}")
                    }
                    detail = CloudDetailView(title, sub, tracks)
                } else {
                    playList(tracks, 0, title)
                    onOpenPlayer()
                }
            }.onFailure {
                accountError = it.message ?: "汽水电台加载失败，请稍后再试"
            }
            radioPlayingBusy = false
        }
    }

    fun openNeteaseMusic(kind: String) {
        if (!netease.connected || loadingDetail) return
        scope.launch {
            loadingDetail = true
            runCatching {
                val tracks = if (kind == "私人 FM") appContainer.neteaseAccount.fm()
                else appContainer.neteaseAccount.heart()
                if (tracks.isEmpty()) throw IllegalStateException("当前账号没有返回可播放歌曲")
                detail = CloudDetailView(kind, "网易云音乐 · ${netease.nickname}", tracks)
            }.onFailure { accountError = it.message ?: "加载失败，请稍后再试" }
            loadingDetail = false
        }
    }

    fun openCloudSonglist(entry: CloudPlaylistEntry) {
        if (loadingDetail) return
        scope.launch {
            loadingDetail = true
            runCatching {
                when (entry.account.source) {
                    SourceID.QS -> appContainer.qishuiAccount.playlistTracks(
                        playlistId = entry.info.id,
                        ownerId = entry.info.ownerId.orEmpty(),
                    )
                    SourceID.TX -> appContainer.qqAccount.playlistTracks(entry.info.id, entry.account.id)
                    else -> appContainer.neteaseAccount.playlistTracks(entry.info.id, entry.account.id)
                }
            }
                .onSuccess { tracks ->
                    detail = CloudDetailView(
                        entry.info.name,
                        "${entry.account.source.cloudFullLabel()} · ${entry.account.name}",
                        tracks,
                    )
                }
                .onFailure { accountError = it.message ?: "歌单加载失败" }
            loadingDetail = false
        }
    }

    val columns = 2
    val recommendedRows = remember(recommended) { recommended.take(12).chunked(columns) }
    val cloudRows = remember(filteredEntries, showAllCloud) {
        (if (showAllCloud) filteredEntries else filteredEntries.take(12)).chunked(columns)
    }

    Box(modifier = modifier.fillMaxSize()) {
        val currentDetail = detail
        if (currentDetail == null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .focusGroup(),
                state = listState,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CloudHeader(
                        qishuiName = qishui.nickname.takeIf { qishui.connected },
                        neteaseName = netease.nickname.takeIf { netease.connected },
                        qqName = qq.name.takeIf { qq.connected },
                        accountCount = accounts.size,
                        onFm = { openNeteaseMusic("私人 FM") },
                        onHeart = { openNeteaseMusic("心动模式") },
                        onLxSync = { showLxSync = true },
                        onManageAccounts = { showAccountPreview = true },
                    )
                }

                // 1. 汽水优先：电台最前（默认仅显示默认模式，支持展开开车/运动/助眠等场景电台）
                if (cloudFilter == CloudFilter.All || cloudFilter == CloudFilter.Qishui) {
                    item(key = "qishui_radio") {
                        QishuiRadioSection(
                            activeMode = activeRadioMode,
                            expanded = radioExpanded,
                            modes = radioModes,
                            modesLoading = radioModesLoading,
                            modesError = radioModesError,
                            busy = radioPlayingBusy,
                            loggedInUser = qishui.nickname.takeIf { qishui.connected },
                            onPlayActive = { launchQishuiRadio(activeRadioMode, openListOnly = false) },
                            onViewActiveList = { launchQishuiRadio(activeRadioMode, openListOnly = true) },
                            onToggleExpand = { radioExpanded = !radioExpanded },
                            onRetryModes = {
                                radioModesLoaded = false
                                radioModesRetryKey += 1
                            },
                            onSelectMode = { mode -> launchQishuiRadio(mode, openListOnly = false) },
                        )
                    }
                }

                // 2. 账号歌单：严格按 汽水 → 网易云 → QQ 排序
                item {
                    CloudLibraryHeader(
                        count = filteredEntries.size,
                        loading = cloudLoading,
                        onRefresh = { cloudRefresh += 1 },
                    )
                }

                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val filters = listOf(
                            CloudFilter.All to "全部",
                            CloudFilter.Qishui to "汽水音乐",
                            CloudFilter.Netease to "网易云",
                            CloudFilter.NeteaseFavorites to "网易云收藏",
                            CloudFilter.Qq to "QQ 音乐",
                        )
                        items(filters, key = { it.first.name }) { (filter, label) ->
                            TvPill(
                                onClick = {
                                    cloudFilter = filter
                                    selectedAccount = null
                                    showAllCloud = false
                                },
                                selected = cloudFilter == filter,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                            ) { Text(label, fontSize = 12.sp) }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("账号", color = AppColors.TextMuted, fontSize = 12.sp)
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "all") {
                                TvPill(
                                    onClick = { selectedAccount = null; showAllCloud = false },
                                    selected = selectedAccount == null,
                                ) { Text("全部账号", fontSize = 12.sp) }
                            }
                            items(scopedAccounts, key = { "${it.source}:${it.id}" }) { account ->
                                val key = "${account.source}:${account.id}"
                                TvPill(
                                    onClick = { selectedAccount = key; showAllCloud = false },
                                    selected = selectedAccount == key,
                                ) {
                                    Text(
                                        "${account.source.cloudShortLabel()} · ${account.name}",
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                if (filteredEntries.isEmpty()) {
                    item {
                        Text(
                            when {
                                cloudLoading -> "正在读取账号歌单…"
                                accounts.isEmpty() -> "连接汽水音乐、网易云或 QQ 音乐后，歌单会显示在这里"
                                cloudError != null && cloudEntries.isEmpty() -> "歌单读取失败，请点击刷新重试"
                                cloudFilter == CloudFilter.Qishui -> "请先在「管理账号」登录汽水音乐，即可同步汽水歌单"
                                cloudFilter == CloudFilter.NeteaseFavorites -> "当前范围没有网易云收藏歌单"
                                else -> "当前范围没有歌单"
                            },
                            color = AppColors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(cloudRows, key = { row ->
                        row.joinToString("|") { "${it.account.source}:${it.account.id}:${it.info.id}" }
                    }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { entry ->
                                val cardKey = "cloud:${entry.account.source}:${entry.account.id}:${entry.info.id}"
                                val cardMod = Modifier
                                    .weight(1f)
                                    .let {
                                        if (lastOpenedCardKey == cardKey) it.focusRequester(lastOpenedCardFocus) else it
                                    }
                                CompactPlaylistCard(
                                    entry.info,
                                    "${entry.account.source.cloudFullLabel()} · ${entry.account.name}",
                                    cardMod,
                                ) {
                                    lastOpenedCardKey = cardKey
                                    openCloudSonglist(entry)
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                if (filteredEntries.size > 12) {
                    item {
                        TvPill(onClick = { showAllCloud = !showAllCloud }) {
                            Text(
                                if (showAllCloud) "收起歌单" else "查看全部 ${filteredEntries.size} 个歌单",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                if (cloudError != null) {
                    item {
                        Text(
                            cloudError.orEmpty(),
                            color = AppColors.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }

                // 3. 网易云为你推荐歌单（放在账号歌单之后，保持汽水电台与歌单在最前面）
                if (netease.connected &&
                    (cloudFilter == CloudFilter.All ||
                        cloudFilter == CloudFilter.Netease ||
                        cloudFilter == CloudFilter.NeteaseFavorites)
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "网易云为你推荐",
                                color = AppColors.TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "网易云 · ${netease.nickname}",
                                color = AppColors.TextMuted,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            TvPill(onClick = { recommendRefresh += 1 }) { Text("刷新", fontSize = 12.sp) }
                        }
                    }
                    if (recommended.isEmpty()) {
                        item {
                            Text(
                                when {
                                    recommendLoading -> "正在加载推荐歌单…"
                                    recommendError != null -> "推荐歌单暂时不可用：$recommendError"
                                    else -> "暂无推荐歌单"
                                },
                                color = AppColors.TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    } else {
                        items(recommendedRows, key = { row ->
                            "recommend:" + row.joinToString("|") { it.id }
                        }) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { info ->
                                    val cardKey = "rec:${info.id}"
                                    val cardMod = Modifier
                                        .weight(1f)
                                        .let {
                                            if (lastOpenedCardKey == cardKey) it.focusRequester(lastOpenedCardFocus) else it
                                        }
                                    CompactPlaylistCard(info, "网易云 · 为你推荐", cardMod) {
                                        lastOpenedCardKey = cardKey
                                        netease.activeId?.let { id ->
                                            openCloudSonglist(
                                                CloudPlaylistEntry(
                                                    CloudAccountOption(SourceID.WY, id, netease.nickname),
                                                    info,
                                                ),
                                            )
                                        }
                                    }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                item {
                    AccountManageEntry(accounts, onClick = { showAccountPreview = true })
                }
            }
        } else {
            TracksDetailOverlay(
                title = currentDetail.title,
                subtitle = currentDetail.subtitle,
                tracks = currentDetail.tracks,
                onBack = { detail = null },
                onOpenPlayer = onOpenPlayer,
            )
        }

        if (showAccountPreview) {
            CloudAccountDialog(onDismiss = { showAccountPreview = false })
        }

        if (showLxSync) {
            com.walkman.tv.ui.components.LxSyncDialog(onDismiss = { showLxSync = false })
        }

        accountError?.let { message ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { accountError = null },
                title = { Text("账号内容", color = AppColors.TextPrimary) },
                text = { Text(message, color = AppColors.TextSecondary) },
                confirmButton = { TvPill(onClick = { accountError = null }) { Text("知道了") } },
                containerColor = AppColors.BgPanel,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QishuiRadioSection(
    activeMode: QishuiAccount.FeedMode,
    expanded: Boolean,
    modes: List<QishuiAccount.FeedMode>,
    modesLoading: Boolean,
    modesError: String?,
    busy: Boolean,
    loggedInUser: String?,
    onPlayActive: () -> Unit,
    onViewActiveList: () -> Unit,
    onToggleExpand: () -> Unit,
    onRetryModes: () -> Unit,
    onSelectMode: (QishuiAccount.FeedMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.BgPanel)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Radio,
                contentDescription = null,
                tint = AppColors.SourceQs,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "汽水电台",
                color = AppColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (loggedInUser != null) "已登录 · $loggedInUser" else "访客推荐 · 登录后更懂你",
                color = AppColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TvPill(
                onClick = onViewActiveList,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("查看电台曲目", fontSize = 12.sp)
            }
        }

        // Default compact bar: play current mode (defaults to 默认模式) + expand/collapse radio modes
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvFocusable(
                onClick = onPlayActive,
                modifier = Modifier
                    .weight(1f)
                    .height(62.dp),
                shape = RoundedCornerShape(12.dp),
                container = AppColors.SourceQs.copy(alpha = 0.16f),
                focusedContainer = AppColors.SourceQs.copy(alpha = 0.28f),
                focusedScale = 1.02f,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppColors.SourceQs.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = AppColors.SourceQs,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (busy) "正在获取电台歌曲…" else "立即播放 · ${activeMode.name}",
                            color = AppColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (activeMode.isDefault) "默认个性推荐电台 · 每次点击刷新一批新歌"
                            else "${activeMode.group.ifBlank { "场景电台" }} · 点击连播 20 首",
                            color = AppColors.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            TvPill(
                onClick = onToggleExpand,
                selected = expanded,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (expanded) "收起电台类型" else "展开电台类型", fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Expandable scene & preference radio modes (collapsed by default)
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("推荐模式", color = AppColors.TextMuted, fontSize = 12.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val baseModes = listOf(QishuiAccount.DEFAULT_FEED_MODE) + QishuiAccount.BUILTIN_PREFERENCE_MODES
                    baseModes.forEach { m ->
                        val isSel = activeMode.id == m.id && activeMode.preference == m.preference
                        TvPill(
                            onClick = { onSelectMode(m) },
                            selected = isSel,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) { Text(m.name, fontSize = 13.sp) }
                    }
                }

                when {
                    modesLoading -> {
                        Text("正在加载开车、运动、助眠等场景电台…", color = AppColors.TextSecondary, fontSize = 12.sp)
                    }
                    modesError != null && modes.isEmpty() -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("场景电台加载失败：$modesError", color = AppColors.TextSecondary, fontSize = 12.sp)
                            TvPill(onClick = onRetryModes) { Text("重试", fontSize = 12.sp) }
                        }
                    }
                    modes.isNotEmpty() -> {
                        val grouped = remember(modes) {
                            modes.groupBy { it.group.ifBlank { "场景电台" } }
                        }
                        grouped.forEach { (groupTitle, itemsInGroup) ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(groupTitle, color = AppColors.TextMuted, fontSize = 12.sp)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    itemsInGroup.forEach { m ->
                                        val isSel = activeMode.id == m.id && activeMode.preference == null
                                        TvPill(
                                            onClick = { onSelectMode(m) },
                                            selected = isSel,
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        ) {
                                            Text(m.name, fontSize = 13.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudHeader(
    qishuiName: String?,
    neteaseName: String?,
    qqName: String?,
    accountCount: Int,
    onFm: () -> Unit,
    onHeart: () -> Unit,
    onLxSync: () -> Unit,
    onManageAccounts: () -> Unit,
) {
    val activeSummary = listOfNotNull(
        qishuiName?.let { "汽水 · $it" },
        neteaseName?.let { "网易云 · $it" },
        qqName?.let { "QQ · $it" },
    ).joinToString("  |  ")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "登录歌单",
                    color = AppColors.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(12.dp))
                Text("已连接 $accountCount 个账号", color = AppColors.TextMuted, fontSize = 12.sp)
            }
            if (activeSummary.isNotEmpty()) {
                Text(
                    activeSummary,
                    color = AppColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (neteaseName != null) {
            TvPill(onClick = onFm) { Text("私人 FM", fontSize = 12.sp) }
            TvPill(onClick = onHeart) { Text("心动模式", fontSize = 12.sp) }
        }
        TvPill(onClick = onLxSync) { Text("LX 同步", fontSize = 12.sp) }
        TvPill(onClick = onManageAccounts, selected = accountCount == 0) {
            Text("管理账号", fontSize = 12.sp)
        }
    }
}

@Composable
private fun CloudLibraryHeader(count: Int, loading: Boolean, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "账号歌单",
            color = AppColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (loading) "同步中…" else "$count 个 · 汽水 / 网易云 / QQ",
            color = AppColors.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TvPill(onClick = onRefresh) { Text("刷新", fontSize = 12.sp) }
    }
}

@Composable
private fun CompactPlaylistCard(
    info: SonglistInfo,
    sourceLabel: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    TvFocusable(
        onClick = onClick,
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(12.dp),
        container = AppColors.BgPanel,
        focusedScale = 1.02f,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistThumbnail(info.picURL, info.source, Modifier.size(56.dp))
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    info.name,
                    color = AppColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    sourceLabel,
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                info.trackCount?.takeIf { it > 0 }?.let {
                    Text("$it 首", color = AppColors.TextMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun PlaylistThumbnail(picURL: String?, source: SourceID, modifier: Modifier) {
    val url = remember(picURL) {
        picURL?.trim()?.takeIf { it.isNotEmpty() }?.let {
            when {
                it.startsWith("https://") -> it
                it.startsWith("http://") -> "https://" + it.removePrefix("http://")
                it.startsWith("//") -> "https:$it"
                else -> null
            }
        }
    }
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    val request = remember(url, context) {
        coil.request.ImageRequest.Builder(context)
            .data(url)
            .size(112, 112)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(source.tint().copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null && !failed) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
            )
        } else {
            Icon(
                Icons.Filled.CloudQueue,
                contentDescription = null,
                tint = AppColors.TextPrimary.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun AccountManageEntry(
    accounts: List<CloudAccountOption>,
    onClick: () -> Unit,
) {
    TvFocusable(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp),
        shape = RoundedCornerShape(14.dp),
        container = AppColors.BgPanel,
        focusedScale = 1.015f,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = AppColors.BrandPrimary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "管理音乐账号",
                    color = AppColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (accounts.isEmpty()) "添加汽水音乐、网易云或 QQ 音乐账号"
                    else accounts.take(3).joinToString(" · ") { "${it.source.cloudShortLabel()}:${it.name}" },
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("查看 / 添加 ›", color = AppColors.TextMuted, fontSize = 12.sp)
        }
    }
}
