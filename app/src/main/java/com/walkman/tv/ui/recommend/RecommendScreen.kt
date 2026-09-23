package com.walkman.tv.ui.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.walkman.tv.playback.LyricParser
import com.walkman.tv.ui.NavSection
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.Artwork
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.theme.AppColors

@Composable
fun RecommendScreen(
  onNavigate: (NavSection) -> Unit,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // 获取屏幕物理总高度（DP）
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val screenHeight = configuration.screenHeightDp.dp

  // 优化：左侧面板总宽度精调为屏幕高度的 55%
  val panelDynamicWidth = screenHeight * 0.55f

  Row(modifier = modifier.fillMaxSize().padding(vertical = 8.dp)) {
    NowPlayingPanel(
      onOpenPlayer = onOpenPlayer,
      modifier = Modifier.width(panelDynamicWidth).fillMaxHeight()
    )
    Spacer(Modifier.width(16.dp))
    RecommendGrid(
      onNavigate = onNavigate,
      onOpenPlayer = onOpenPlayer,
      modifier = Modifier.weight(1f).fillMaxHeight(),
    )
  }
}

@Composable
private fun NowPlayingPanel(onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
  val controller = appContainer.playbackController
  val state by controller.state.collectAsState()
  val lyrics by controller.lyrics.collectAsState()
  val track = state.currentTrack

  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val screenHeight = configuration.screenHeightDp.dp

  // 优化：封面尺寸精准锁定为屏幕高度的 46%（完美的黄金分割占比）
  val coverDynamicSize = screenHeight * 0.46f

  Column(
    modifier = modifier.padding(start = 0.dp, end = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center // 保持纵向绝对居中，留白非常匀称
  ) {
    // 自适应封面
    TvFocusable(
      onClick = onOpenPlayer,
      modifier = Modifier.size(coverDynamicSize),
      shape = RoundedCornerShape(12.dp),
    ) {
      Artwork(track?.picURL, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp))
    }
    Spacer(Modifier.height(12.dp))
    if (track != null) {
      Text(track.name, color = AppColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      Text(track.singer, color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
      Spacer(Modifier.height(8.dp))
      val active = LyricParser.activeIndex(state.positionMs / 1000.0, lyrics)
      val line = lyrics.getOrNull(active)?.text ?: if (state.resolving) "解析中…" else ""
      Text(line, color = AppColors.LyricIdle, fontSize = 13.sp, maxLines = 1)
    } else {
      Text("暂无播放，去推荐里点歌开始吧", color = AppColors.TextSecondary, fontSize = 14.sp)
    }

    if (track != null) {
      Spacer(Modifier.height(10.dp))
      val audioLevel by controller.audioLevel.collectAsState()

      com.walkman.tv.ui.components.Waveform(
        isPlaying = state.isPlaying,
        level = audioLevel,
        modifier = Modifier.width(coverDynamicSize).height(28.dp),
      )
      Spacer(Modifier.height(6.dp))

      MiniProgressBar(
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        modifier = Modifier.width(coverDynamicSize),
      )
    }
    Spacer(Modifier.height(14.dp))
    // 底部控制按钮
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CircleControl(Icons.Filled.SkipPrevious) { controller.prev() }
      Spacer(Modifier.width(20.dp))
      CircleControl(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow) { controller.togglePlay() }
      Spacer(Modifier.width(20.dp))
      CircleControl(Icons.Filled.SkipNext) { controller.next() }
    }
  }
}

/** 只读进度条 */
@Composable
private fun MiniProgressBar(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
  val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
  Column(modifier = modifier) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(2.dp)
        .clip(RoundedCornerShape(2.dp))
        .background(AppColors.Card),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth(fraction)
          .fillMaxHeight()
          .background(AppColors.AccentGreen),
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(fmtMillis(positionMs), color = AppColors.TextMuted, fontSize = 10.sp)
      Text(fmtMillis(durationMs), color = AppColors.TextMuted, fontSize = 10.sp)
    }
  }
}

private fun fmtMillis(ms: Long): String {
  val s = (ms / 1000).toInt().coerceAtLeast(0)
  return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
private fun CircleControl(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
  TvPill(
    onClick = onClick,
    shape = androidx.compose.foundation.shape.CircleShape,
    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
  }
}

// ============== 右侧推荐内容 =====================================================

private data class DetailView(val title: String, val subtitle: String?, val tracks: List<com.walkman.tv.data.model.Track>)

@Composable
private fun RecommendGrid(
  onNavigate: (NavSection) -> Unit,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val settings by appContainer.settingsStore.settings.collectAsState()
  val home by appContainer.homeStore.state.collectAsState()
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var detail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<DetailView?>(null) }
  var loadingDetail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  var showAccountPreview by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val netease by appContainer.neteaseAccount.state.collectAsState()
  val kugou by appContainer.kugouAccount.state.collectAsState()
  val activeSource by appContainer.cloudSelection.source.collectAsState()
  val connected = if (activeSource == com.walkman.tv.data.model.SourceID.KG) kugou.connected else netease.connected
  val accountName = if (activeSource == com.walkman.tv.data.model.SourceID.KG) "酷狗 ${kugou.userId}" else netease.nickname
  var accountError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
  var cloudLists by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.walkman.tv.data.model.SonglistInfo>?>(null) }

  fun openPersonal(kind: String) {
    if (!connected) { showAccountPreview = true; return }
    if (loadingDetail) return
    scope.launch {
      loadingDetail = true
      runCatching {
        if (activeSource == com.walkman.tv.data.model.SourceID.KG) {
          when (kind) {
            "每日推荐" -> detail = DetailView(kind, "酷狗音乐", appContainer.kugouAccount.daily())
            "私人 FM" -> detail = DetailView(kind, "酷狗音乐", appContainer.kugouAccount.fm())
            "云端歌单" -> cloudLists = appContainer.kugouAccount.playlists()
            else -> accountError = "酷狗音乐暂未提供这个入口，可切换到网易云账号使用。"
          }
        } else {
          when (kind) {
            "每日推荐" -> detail = DetailView(kind, "网易云音乐", appContainer.neteaseAccount.daily())
            "私人 FM" -> detail = DetailView(kind, "网易云音乐", appContainer.neteaseAccount.fm())
            "心动模式" -> detail = DetailView(kind, "网易云音乐", appContainer.neteaseAccount.heart())
            "猜你喜欢" -> detail = DetailView(kind, "网易云音乐", appContainer.neteaseAccount.guessLike())
            "云端歌单" -> cloudLists = appContainer.neteaseAccount.playlists()
          }
        }
      }.onFailure { accountError = it.message ?: "加载失败，请稍后再试" }
      loadingDetail = false
    }
  }

  androidx.compose.runtime.LaunchedEffect(settings.homeSources) {
    appContainer.homeStore.loadIfNeeded(settings.homeSources)
  }

  val activeLabel = home.sources.joinToString(" · ") { it.displayName }

  fun openSonglist(info: com.walkman.tv.data.model.SonglistInfo) {
    if (loadingDetail) return
    scope.launch {
      loadingDetail = true
      val svc = appContainer.songlists.serviceFor(info.source)
      val d = runCatching { svc?.fetchDetail(info) }.getOrNull()
      detail = DetailView(
        title = info.name,
        subtitle = info.author.takeIf { it.isNotBlank() } ?: info.source.displayName,
        tracks = d?.tracks ?: emptyList(),
      )
      loadingDetail = false
    }
  }

  fun openCloudSonglist(info: com.walkman.tv.data.model.SonglistInfo) {
    if (loadingDetail) return
    scope.launch {
      loadingDetail = true
      runCatching {
        if (info.source == com.walkman.tv.data.model.SourceID.KG)
          appContainer.kugouAccount.playlistTracks(info.id)
        else appContainer.neteaseAccount.playlistTracks(info.id)
      }
        .onSuccess { tracks -> detail = DetailView(info.name, info.author, tracks) }
        .onFailure { accountError = it.message ?: "歌单加载失败" }
      loadingDetail = false
    }
  }

  fun openBoard(board: com.walkman.tv.data.model.BoardInfo) {
    if (loadingDetail) return
    scope.launch {
      loadingDetail = true
      val svc = appContainer.boards.serviceFor(board.source)
      val tracks = runCatching { svc?.fetchTracks(board.bangid, 1) ?: emptyList() }.getOrDefault(emptyList())
      detail = DetailView(
        title = board.name,
        subtitle = board.source.displayName,
        tracks = tracks,
      )
      loadingDetail = false
    }
  }

  Box(modifier = modifier) {
    androidx.compose.foundation.lazy.LazyColumn(
      modifier = Modifier.fillMaxSize().focusGroup(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        PersonalizedIntro(
          onOpenAccount = { showAccountPreview = true },
          onSelectPersonal = ::openPersonal,
          connectedName = accountName.takeIf { connected },
          onNavigate = onNavigate,
        )
      }
      when {
        settings.homeSources.isEmpty() -> item { NoSourcesHint(onNavigate) }
        home.isLoading && home.heroes.isEmpty() -> item { LoadingPlaceholder() }
        else -> {
          if (home.heroes.isNotEmpty()) {
            item {
              HeroCarousel(
                heroes = home.heroes,
                onSelect = { hero -> openSonglist(hero.songlist) },
              )
            }
          }
          if (home.recommendations.isNotEmpty()) {
            item {
              SectionHeader("推荐歌单", activeLabel, trailing = "查看全部") {
                onNavigate(NavSection.Songlist)
              }
            }
            item {
              SonglistRow(home.recommendations) { info -> openSonglist(info) }
            }
          }
          if (home.boards.isNotEmpty()) {
            item {
              SectionHeader("排行榜", activeLabel, trailing = "查看全部") {
                onNavigate(NavSection.Leaderboard)
              }
            }
            item {
              BoardRow(home.boards) { board -> openBoard(board) }
            }
          }
          item { StatsRow(onNavigate) }
        }
      }
    }

    if (showAccountPreview) {
      AccountConnectDialog(onDismiss = { showAccountPreview = false })
    }

    cloudLists?.let { lists ->
      CloudPlaylistDialog(lists, onDismiss = { cloudLists = null }) { info ->
        cloudLists = null
        openCloudSonglist(info)
      }
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

    detail?.let { d ->
      com.walkman.tv.ui.components.TracksDetailOverlay(
        title = d.title,
        subtitle = d.subtitle,
        tracks = d.tracks,
        onBack = { detail = null },
        onOpenPlayer = onOpenPlayer,
      )
    }
  }
}

/**
 * Account and personal music entry points stay visible even while public recommendations load.
 * Personal tiles use the connected account's catalog, while public sections stay available.
 */
@Composable
private fun PersonalizedIntro(
  onOpenAccount: () -> Unit,
  onSelectPersonal: (String) -> Unit,
  connectedName: String?,
  onNavigate: (NavSection) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    TvFocusable(
      onClick = onOpenAccount,
      modifier = Modifier.fillMaxWidth().height(68.dp),
      shape = RoundedCornerShape(18.dp),
      container = AppColors.BgPanel,
    ) {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
            .background(AppColors.BrandPrimary.copy(alpha = 0.17f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(Icons.Filled.AccountCircle, contentDescription = null,
            tint = AppColors.BrandPrimary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(connectedName ?: "连接你的音乐", color = AppColors.TextPrimary, fontSize = 17.sp,
            fontWeight = FontWeight.Bold)
          Text("网易云 · 酷狗音乐", color = AppColors.TextSecondary, fontSize = 12.sp)
        }
        Box(
          modifier = Modifier.clip(RoundedCornerShape(50))
            .background(AppColors.Card).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
          Text(if (connectedName == null) "尚未连接" else "已连接", color = AppColors.TextSecondary, fontSize = 12.sp)
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      Text("你的音乐", color = AppColors.TextPrimary, fontSize = 21.sp,
        fontWeight = FontWeight.Bold)
      Spacer(Modifier.width(10.dp))
      Text(if (connectedName == null) "连接账号后开启" else "来自你的音乐账号", color = AppColors.TextMuted, fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 3.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PersonalTile("每日推荐", "每天一份新鲜歌单", Icons.Filled.Today,
        Modifier.weight(1f)) { onSelectPersonal("每日推荐") }
      PersonalTile("私人 FM", "随心听下一首", Icons.Filled.Radio,
        Modifier.weight(1f)) { onSelectPersonal("私人 FM") }
      PersonalTile("心动模式", "从喜欢出发", Icons.Filled.Favorite,
        Modifier.weight(1f)) { onSelectPersonal("心动模式") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PersonalTile("猜你喜欢", "发现合口味的歌", Icons.Filled.AutoAwesome,
        Modifier.weight(1f)) { onSelectPersonal("猜你喜欢") }
      PersonalTile("云端歌单", "同步收藏与自建", Icons.Filled.CloudQueue,
        Modifier.weight(1f)) { onSelectPersonal("云端歌单") }
      PersonalTile("精选歌单", "发现更多好音乐", Icons.Filled.LibraryMusic,
        Modifier.weight(1f)) { onNavigate(NavSection.Songlist) }
    }
  }
}

@Composable
private fun PersonalTile(
  title: String,
  subtitle: String,
  icon: ImageVector,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  TvFocusable(
    onClick = onClick,
    modifier = modifier.height(104.dp),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 11.dp),
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Icon(icon, contentDescription = null, tint = AppColors.BrandPrimary,
        modifier = Modifier.size(23.dp))
      Column {
        Text(title, color = AppColors.TextPrimary, fontSize = 16.sp,
          fontWeight = FontWeight.Bold, maxLines = 1)
        Text(subtitle, color = AppColors.TextSecondary, fontSize = 11.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun AccountConnectDialog(onDismiss: () -> Unit) {
  val neteaseAccount = appContainer.neteaseAccount
  val kugouAccount = appContainer.kugouAccount
  val netease by neteaseAccount.state.collectAsState()
  val kugou by kugouAccount.state.collectAsState()
  val active by appContainer.cloudSelection.source.collectAsState()
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var qrKey by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
  var qrSource by androidx.compose.runtime.remember {
    androidx.compose.runtime.mutableStateOf(com.walkman.tv.data.model.SourceID.WY)
  }
  var status by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("请扫码登录") }
  var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

  androidx.compose.runtime.LaunchedEffect(qrKey, qrSource) {
    val key = qrKey ?: return@LaunchedEffect
    while (true) {
      kotlinx.coroutines.delay(2000)
      runCatching {
        if (qrSource == com.walkman.tv.data.model.SourceID.KG) kugouAccount.pollQr(key)
        else neteaseAccount.pollQr(key)
      }.onSuccess { code ->
        val success = if (qrSource == com.walkman.tv.data.model.SourceID.KG) code == 4 else code == 803
        val expired = if (qrSource == com.walkman.tv.data.model.SourceID.KG) code == 0 else code == 800
        when {
          success -> {
            appContainer.cloudSelection.select(qrSource)
            qrKey = null
            onDismiss()
            return@LaunchedEffect
          }
          expired -> {
            qrKey = null
            error = "二维码已过期，请重新生成"
            return@LaunchedEffect
          }
          code == 2 || code == 802 -> status = "已扫码，请在手机上确认"
        }
      }.onFailure {
        qrKey = null
        error = it.message ?: "登录失败"
        return@LaunchedEffect
      }
    }
  }

  fun startQr(source: com.walkman.tv.data.model.SourceID) {
    if (busy) return
    scope.launch {
      busy = true
      error = null
      runCatching {
        if (source == com.walkman.tv.data.model.SourceID.KG) kugouAccount.newQr()
        else neteaseAccount.newQr()
      }.onSuccess {
        qrSource = source
        qrKey = it
        status = "请用\${source.displayName}扫码"
      }.onFailure { error = it.message ?: "无法生成二维码" }
      busy = false
    }
  }

  qrKey?.let { key ->
    val url = if (qrSource == com.walkman.tv.data.model.SourceID.KG)
      kugouAccount.qrUrl(key) else neteaseAccount.qrUrl(key)
    com.walkman.tv.ui.components.QrDialog(
      url = url,
      title = "\${qrSource.displayName}扫码登录",
      subtitle = status,
      onDismiss = { qrKey = null },
    )
    return
  }
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(
      modifier = Modifier.width(480.dp).clip(RoundedCornerShape(20.dp))
        .background(AppColors.BgPanel).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("连接音乐账号", color = AppColors.TextPrimary, fontSize = 22.sp,
        fontWeight = FontWeight.Bold)
      Text("扫码连接后可使用你的专属推荐与云端歌单",
        color = AppColors.TextSecondary, fontSize = 14.sp)
      AccountPlatformButton(
        "网易云音乐", if (netease.connected) netease.nickname else "扫码连接",
        active == com.walkman.tv.data.model.SourceID.WY && netease.connected,
        onClick = {
          if (netease.connected) appContainer.cloudSelection.select(com.walkman.tv.data.model.SourceID.WY)
          else startQr(com.walkman.tv.data.model.SourceID.WY)
        },
      )
      AccountPlatformButton(
        "酷狗音乐", if (kugou.connected) "账号 \${kugou.userId}" else "扫码连接",
        active == com.walkman.tv.data.model.SourceID.KG && kugou.connected,
        onClick = {
          if (kugou.connected) appContainer.cloudSelection.select(com.walkman.tv.data.model.SourceID.KG)
          else startQr(com.walkman.tv.data.model.SourceID.KG)
        },
      )
      Text("QQ 音乐账号接口尚未接通", color = AppColors.TextMuted, fontSize = 12.sp)
      error?.let { Text(it, color = AppColors.BrandPrimary, fontSize = 13.sp) }
      if (busy) Text("正在生成二维码…", color = AppColors.TextSecondary, fontSize = 13.sp)
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (netease.connected || kugou.connected) {
          TvPill(onClick = {
            if (active == com.walkman.tv.data.model.SourceID.KG) kugouAccount.disconnect()
            else neteaseAccount.disconnect()
            onDismiss()
          }) { Text("断开当前账号", fontSize = 14.sp) }
          Spacer(Modifier.width(12.dp))
        }
        TvPill(onClick = onDismiss) { Text("关闭", fontSize = 14.sp) }
      }
    }
  }
}

@Composable
private fun AccountPlatformButton(
  title: String, status: String, selected: Boolean, onClick: () -> Unit,
) {
  TvFocusable(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(54.dp),
    shape = RoundedCornerShape(12.dp),
  ) {
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically) {
      Text(title, color = AppColors.TextPrimary, fontSize = 16.sp,
        modifier = Modifier.weight(1f))
      Text(if (selected) "使用中" else status, color = AppColors.BrandPrimary,
        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun CloudPlaylistDialog(
  lists: List<com.walkman.tv.data.model.SonglistInfo>,
  onDismiss: () -> Unit,
  onSelect: (com.walkman.tv.data.model.SonglistInfo) -> Unit,
) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(Modifier.width(560.dp).height(520.dp).clip(RoundedCornerShape(18.dp))
      .background(AppColors.BgPanel).padding(20.dp)) {
      Text("云端歌单", color = AppColors.TextPrimary, fontSize = 20.sp,
        fontWeight = FontWeight.Bold)
      Spacer(Modifier.height(12.dp))
      if (lists.isEmpty()) Text("账号下暂无歌单", color = AppColors.TextSecondary)
      androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(lists) { info ->
          TvFocusable(onClick = { onSelect(info) }, modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 8.dp)) {
              Text(info.name, color = AppColors.TextPrimary, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text("\${info.trackCount ?: 0} 首 · \${info.author}", color = AppColors.TextSecondary,
                fontSize = 12.sp, maxLines = 1)
            }
          }
        }
      }
      TvPill(onClick = onDismiss) { Text("返回", fontSize = 14.sp) }
    }
  }
}

@Composable
private fun StatsRow(onNavigate: (NavSection) -> Unit) {
  val played by appContainer.libraryStore.history.collectAsState()
  val loved by appContainer.libraryStore.love.collectAsState()
  Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxWidth().height(72.dp),
  ) {
    StatTile(
      label = "已播",
      count = played.tracks.size,
      picURL = StatThumbs.PLAYED,
      modifier = Modifier.weight(1f),
    ) { onNavigate(NavSection.Library) }
    StatTile(
      label = "收藏",
      count = loved.tracks.size,
      picURL = StatThumbs.LOVE,
      modifier = Modifier.weight(1f),
    ) { onNavigate(NavSection.Library) }
  }
}

private object StatThumbs {
  const val PLAYED = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=200&q=60"
  const val LOVE   = "https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=200&q=60"
}

@Composable
private fun StatTile(label: String, count: Int, picURL: String, modifier: Modifier, onClick: () -> Unit) {
  TvFocusable(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(36.dp)) {
    Row(
      modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 20.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(50))) {
        AsyncImage(
          model = picURL,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }
      Spacer(Modifier.width(12.dp))
      Text(
        label,
        color = AppColors.TextSecondary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      Text(
        "$count",
        color = AppColors.TextPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun SectionHeader(
  title: String,
  subtitle: String?,
  trailing: String?,
  onTrailingClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
    verticalAlignment = Alignment.Bottom,
  ) {
    Text(title, color = AppColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    if (!subtitle.isNullOrBlank()) {
      Spacer(Modifier.width(10.dp))
      Text(
        subtitle,
        color = AppColors.TextMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 4.dp),
      )
    }
    Spacer(Modifier.weight(1f))
    if (!trailing.isNullOrBlank()) {
      TvPill(
        onClick = onTrailingClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
      ) {
        Text(trailing, fontSize = 12.sp)
      }
    }
  }
}

@Composable
private fun HeroCarousel(heroes: List<HeroItem>, onSelect: (HeroItem) -> Unit) {
  var index by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
  var hasFocus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val bannerFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }

  androidx.compose.runtime.LaunchedEffect(Unit) {
    runCatching { bannerFocus.requestFocus() }
  }

  androidx.compose.runtime.LaunchedEffect(heroes.size, index, hasFocus) {
    if (heroes.size > 1 && !hasFocus) {
      kotlinx.coroutines.delay(6_000)
      index = (index + 1) % heroes.size
    }
  }
  val current = heroes.getOrNull(index) ?: return

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .onFocusChanged { hasFocus = it.hasFocus },
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    HeroBanner(item = current, focusRequester = bannerFocus, onClick = { onSelect(current) })
    if (heroes.size > 1) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      ) {
        heroes.forEachIndexed { i, _ ->
          val active = i == index
          TvFocusable(
            onClick = { index = i },
            shape = RoundedCornerShape(50),
          ) {
            Box(
              modifier = Modifier
                .width(if (active) 28.dp else 12.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(
                  if (active) AppColors.BrandPrimary
                  else AppColors.TextMuted.copy(alpha = 0.5f),
                ),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun HeroBanner(
  item: HeroItem,
  focusRequester: androidx.compose.ui.focus.FocusRequester,
  onClick: () -> Unit,
) {
  val tint = item.source.tintColor()
  val accentEnd = tint.copy(alpha = 0.65f)
  TvFocusable(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .height(260.dp)
      .focusRequester(focusRequester),
    shape = RoundedCornerShape(20.dp),
  ) {
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))) {
      Box(
        modifier = Modifier.fillMaxSize().background(
          Brush.linearGradient(
            colors = listOf(tint, accentEnd, Color.Black.copy(alpha = 0.85f)),
          ),
        ),
      )
      item.songlist.picURL?.let {
        AsyncImage(
          model = it,
          contentDescription = null,
          modifier = Modifier.fillMaxSize().alpha(0.18f),
          contentScale = ContentScale.Crop,
        )
      }
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            item.songlist.name,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.height(8.dp))
          val subParts = buildList {
            add(item.source.displayName)
            val plays = item.songlist.playCount
            if (!plays.isNullOrBlank()) add(plays) else add("热门播放")
          }
          Text(
            subParts.joinToString(" · "),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.height(16.dp))
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .background(Color.White)
              .padding(horizontal = 18.dp, vertical = 10.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(4.dp))
              Text("立即播放", color = tint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
        Spacer(Modifier.width(20.dp))
        Box(
          modifier = Modifier.size(180.dp).clip(RoundedCornerShape(14.dp)),
        ) {
          item.songlist.picURL?.let {
            AsyncImage(
              model = it,
              contentDescription = null,
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
            )
          } ?: Artwork(null, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(14.dp))
        }
      }
    }
  }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SonglistRow(items: List<com.walkman.tv.data.model.SonglistInfo>, onSelect: (com.walkman.tv.data.model.SonglistInfo) -> Unit) {
  androidx.compose.foundation.lazy.LazyRow(
    modifier = Modifier.focusRestorer(),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp),
  ) {
    items(items, key = { "${it.source.key}_${it.id}" }) { info ->
      AlbumCard(
        picURL = info.picURL,
        title = info.name,
        subtitle = info.playCount?.let { "▶ $it" } ?: info.source.displayName,
        fallbackTint = info.source.tintColor(),
        onClick = { onSelect(info) },
      )
    }
  }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BoardRow(items: List<com.walkman.tv.data.model.BoardInfo>, onSelect: (com.walkman.tv.data.model.BoardInfo) -> Unit) {
  androidx.compose.foundation.lazy.LazyRow(
    modifier = Modifier.focusRestorer(),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp),
  ) {
    items(items, key = { "${it.source.key}_${it.id}" }) { board ->
      AlbumCard(
        picURL = board.picURL,
        title = board.name,
        subtitle = board.source.displayName,
        fallbackTint = board.source.tintColor(),
        onClick = { onSelect(board) },
      )
    }
  }
}

@Composable
private fun AlbumCard(
  picURL: String?,
  title: String,
  subtitle: String?,
  fallbackTint: Color,
  onClick: () -> Unit,
) {
  Column(modifier = Modifier.width(170.dp)) {
    TvFocusable(onClick = onClick, modifier = Modifier.size(170.dp), shape = RoundedCornerShape(12.dp)) {
      Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
        if (!picURL.isNullOrBlank()) {
          AsyncImage(
            model = picURL,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
          )
        } else {
          Box(
            modifier = Modifier.fillMaxSize().background(
              Brush.linearGradient(listOf(fallbackTint, fallbackTint.copy(alpha = 0.5f))),
            ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              Icons.Filled.PlayArrow,
              contentDescription = null,
              tint = Color.White.copy(alpha = 0.7f),
              modifier = Modifier.size(40.dp),
            )
          }
        }
      }
    }
    Spacer(Modifier.height(8.dp))
    Text(
      title,
      color = AppColors.TextPrimary,
      fontSize = 16.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        subtitle,
        color = AppColors.TextMuted,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun LoadingPlaceholder() {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth().height(260.dp)
        .clip(RoundedCornerShape(20.dp))
        .background(AppColors.Card),
    )
    repeat(2) {
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(4) {
          Box(
            modifier = Modifier
              .size(170.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(AppColors.Card),
          )
        }
      }
    }
  }
}

@Composable
private fun NoSourcesHint(onNavigate: (NavSection) -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("没有勾选任何音源", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("去设置里勾选一个音源后再来发现", color = AppColors.TextMuted, fontSize = 13.sp)
    Spacer(Modifier.height(16.dp))
    TvPill(onClick = { onNavigate(NavSection.Settings) }, selected = true) {
      Text("打开设置", fontSize = 13.sp)
    }
  }
}

private fun com.walkman.tv.data.model.SourceID.tintColor(): Color = when (this) {
  com.walkman.tv.data.model.SourceID.KW -> AppColors.SourceKw
  com.walkman.tv.data.model.SourceID.KG -> AppColors.SourceKg
  com.walkman.tv.data.model.SourceID.TX -> AppColors.SourceTx
  com.walkman.tv.data.model.SourceID.WY -> AppColors.SourceWy
  com.walkman.tv.data.model.SourceID.MG -> AppColors.SourceMg
  com.walkman.tv.data.model.SourceID.LOCAL -> AppColors.SourceLocal
}
