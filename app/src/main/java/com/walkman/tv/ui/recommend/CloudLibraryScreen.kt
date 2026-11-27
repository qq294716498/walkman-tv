package com.walkman.tv.ui.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.TracksDetailOverlay
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.components.tint
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
private enum class CloudFilter { All, NeteaseFavorites, Qq }

/**
 * Dedicated top-level screen ("登录歌单") hosting all logged-in QQ Music & NetEase Cloud Music
 * features: account management, NetEase Private FM / Heart mode, personalized recommendations,
 * and synced account playlists.
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
    val netease by appContainer.neteaseAccount.state.collectAsState()
    val qq by appContainer.qqAccount.state.collectAsState()

    val accounts = remember(netease.accounts, qq.accounts) {
        netease.accounts.map { CloudAccountOption(SourceID.WY, it.id, it.nickname) } +
            qq.accounts.map { CloudAccountOption(SourceID.TX, it.id, it.name) }
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
                CloudFilter.NeteaseFavorites -> it.source == SourceID.WY
                CloudFilter.Qq -> it.source == SourceID.TX
            }
        }
    }
    val filteredEntries = remember(cloudEntries, selectedAccount, cloudFilter) {
        cloudEntries.filter { entry ->
            (selectedAccount == null || selectedAccount == "${entry.account.source}:${entry.account.id}") &&
                when (cloudFilter) {
                    CloudFilter.All -> true
                    CloudFilter.NeteaseFavorites -> entry.account.source == SourceID.WY && entry.isFavorite
                    CloudFilter.Qq -> entry.account.source == SourceID.TX
                }
        }
    }

    LaunchedEffect(netease.accounts, qq.accounts, cloudRefresh) {
        val loadKey = accounts.map { "${it.source}:${it.id}:${it.name}" } to cloudRefresh
        if (loadedCloudKey == loadKey) return@LaunchedEffect
        cloudLoading = true
        cloudError = null
        cloudEntries = emptyList()
        showAllCloud = false
        val loaded = mutableListOf<CloudPlaylistEntry>()
        val failures = mutableListOf<String>()
        for (account in accounts) {
            if (account.source == SourceID.TX) {
                runCatching { appContainer.qqAccount.playlists(account.id) }
                    .onSuccess { lists -> loaded += lists.map { CloudPlaylistEntry(account, it) } }
            } else {
                runCatching { appContainer.neteaseAccount.playlistsWithKinds(account.id) }
                    .onSuccess { lists -> loaded += lists.map { CloudPlaylistEntry(account, it.info, it.subscribed) } }
                    .onFailure { failures += "网易云 · ${account.name}: ${it.message ?: "读取失败"}" }
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
                if (entry.account.source == SourceID.TX)
                    appContainer.qqAccount.playlistTracks(entry.info.id, entry.account.id)
                else appContainer.neteaseAccount.playlistTracks(entry.info.id, entry.account.id)
            }
                .onSuccess { tracks ->
                    detail = CloudDetailView(
                        entry.info.name,
                        "${if (entry.account.source == SourceID.TX) "QQ音乐" else "网易云"} · ${entry.account.name}",
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
                modifier = Modifier.fillMaxSize().focusGroup(),
                state = listState,
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CloudHeader(
                        neteaseName = netease.nickname.takeIf { netease.connected },
                        accountCount = accounts.size,
                        onFm = { openNeteaseMusic("私人 FM") },
                        onHeart = { openNeteaseMusic("心动模式") },
                        onManageAccounts = { showAccountPreview = true },
                    )
                }

                if (netease.connected) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "为你推荐的歌单",
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
                                    val cardMod = Modifier.weight(1f).let {
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
                    CloudLibraryHeader(
                        count = filteredEntries.size,
                        loading = cloudLoading,
                        onRefresh = { cloudRefresh += 1 },
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            CloudFilter.All to "全部",
                            CloudFilter.NeteaseFavorites to "网易云收藏",
                            CloudFilter.Qq to "QQ 音乐",
                        ).forEach { (filter, label) ->
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
                                        "${if (account.source == SourceID.TX) "QQ" else "网易云"} · ${account.name}",
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
                                accounts.isEmpty() -> "连接 QQ 音乐或网易云后，歌单会显示在这里"
                                cloudError != null && cloudEntries.isEmpty() -> "歌单读取失败，请点击刷新重试"
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
                                val cardMod = Modifier.weight(1f).let {
                                    if (lastOpenedCardKey == cardKey) it.focusRequester(lastOpenedCardFocus) else it
                                }
                                CompactPlaylistCard(
                                    entry.info,
                                    "${if (entry.account.source == SourceID.TX) "QQ音乐" else "网易云"} · ${entry.account.name}",
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

                if (cloudFilter != CloudFilter.Qq && cloudError != null) {
                    item {
                        Text(
                            cloudError.orEmpty(),
                            color = AppColors.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
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

@Composable
private fun CloudHeader(
    neteaseName: String?,
    accountCount: Int,
    onFm: () -> Unit,
    onHeart: () -> Unit,
    onManageAccounts: () -> Unit,
) {
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
            if (neteaseName != null) {
                Text(
                    "网易云 · $neteaseName",
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
            if (loading) "同步中…" else "$count 个 · QQ音乐 / 网易云",
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
    ) {
        Row(
            Modifier.fillMaxSize().padding(9.dp),
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
        modifier = Modifier.fillMaxWidth().height(70.dp),
        shape = RoundedCornerShape(14.dp),
        container = AppColors.BgPanel,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
                    if (accounts.isEmpty()) "添加 QQ 音乐或网易云账号"
                    else accounts.take(3).joinToString(" · ") { it.name },
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
