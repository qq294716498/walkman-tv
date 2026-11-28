package com.walkman.tv.data.store

import android.content.Context
import com.walkman.tv.data.model.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** Lyric font-size preset for the full-screen player. [activeSp]/[inactiveSp]/[translationSp]
 *  are the rendered sizes for the current line / other lines / translation line. STANDARD matches
 *  the original hard-coded 22/15/12. */
@Serializable
enum class LyricSize(val label: String, val activeSp: Int, val inactiveSp: Int, val translationSp: Int) {
    STANDARD("标准", 22, 15, 12),
    LARGE("大", 30, 19, 15),
    MAX("最大", 40, 24, 18),
}

/** Color theme for the player's spinning vinyl disc. GREEN is the original look and the default;
 *  BLACK (a real record) was requested in #8. Palettes live in PlayerScreen. */
@Serializable
enum class VinylColor(val label: String) {
    GREEN("翠绿"),
    BLACK("经典黑"),
    RED("酒红"),
    BLUE("深蓝"),
    GOLD("琥珀金"),
}

/** Global UI accent color theme. GREEN is the original look and the default. */
@Serializable
enum class ThemeColor(
    val label: String,
    val primaryArgb: Long,
    val brightArgb: Long,
    val neonArgb: Long,
) {
    GREEN("翠绿", 0xFF4ADE80, 0xFF7EE7A4, 0xFF39FF14),
    BLUE("极光蓝", 0xFF38BDF8, 0xFF7DD3FC, 0xFF0EA5E9),
    PURPLE("星云紫", 0xFFA78BFA, 0xFFC4B5FD, 0xFF8B5CF6),
    AMBER("琥珀金", 0xFFFBBF24, 0xFFFDE047, 0xFFF59E0B),
    CORAL("珊瑚红", 0xFFFB7185, 0xFFFDA4AF, 0xFFF43F5E),
    CYAN("青碧", 0xFF2DD4BF, 0xFF5EEAD4, 0xFF14B8A6),
}

/** Lyric rendering styles in PlayerScreen. */
@Serializable
enum class LyricStyle(val label: String) {
    CLASSIC("经典"),
    APPLE("类苹果 (AMLL)"),
    FOLIA_WEBVIEW("Folia 舞台 (WebView)"),
}

/** All 18 Folia lyric visualizer templates (Plan B WebView GPU stage). */
@Serializable
enum class FoliaTemplate(
    val id: String,
    val label: String,
    val desc: String,
) {
    CLASSIC("classic", "经典 · Classic", "居中流光高亮与柔和渐变舞台"),
    STILL("still", "静止 · Still", "歌词静止在画面中央，无动画最省资源"),
    CADENZA("cadenza", "心象 · Cadenza", "整句拆成单字散落画面，如脑海浮起的碎片"),
    PARTITA("partita", "云阶 · Partita", "沿引导线逐块错落下行，像云中阶梯"),
    FUME("fume", "浮名 · Fume", "满屏歌词层层堆叠，镜头追随当前句浮沉"),
    CAPPELLA("cappella", "群唱 · Cappella", "聊天气泡左右往返，像一场对唱"),
    TILT("tilt", "倾诉 · Tilt", "自动断行与倾斜强调，如凑近低声倾诉"),
    CLADDAGH("claddagh", "回环 · Claddagh", "沿倾斜 3D 圆环轨道循环推进，纵深感强"),
    MONET("monet", "莫奈 · Monet", "海报式版面：左侧歌词轨、右侧封面、底部频谱"),
    DIORAMA("diorama", "镜台 · Diorama", "点阵化文字在 3D 空间穿行，镜头跟随运镜"),
    PENDOLO("pendolo", "时计 · Pendolo", "封面嵌入钟表盘，歌词沿弧线随擒纵推进"),
    SONNET("sonnet", "商籁 · Sonnet", "短线、边框与镂空巨字铺开，如翻动诗稿"),
    TEMPERA("tempera", "凝彩 · Tempera", "色块与细密网点重组，文字压过色块反色"),
    LUMIERE("lumiere", "绘光 · Lumiere", "体积光束穿过烟雾浮尘，逐字照亮刻字"),
    NEBBIA("nebbia", "雾笺 · Nebbia", "毛玻璃与水雾层叠，当前句拨雾浮现"),
    METAPHOR("metaphor", "隐喻 · Metaphor", "极简电影画幅与几何引导线，强烈留白对比"),
    HANABI("hanabi", "花火 · Hanabi", "夜空粒子花火随乐句与节拍绽放"),
    MACHI("machi", "町景 · Machi", "都市夜色霓虹灯牌构图与动态运镜"),
}

/** 6 Folia dynamic background modes (matching folia-android BUILTIN_VISUALIZER_BACKGROUND_MODES). */
@Serializable
enum class FoliaBackground(
    val id: String,
    val label: String,
    val desc: String,
    val previewColorA: Long,
    val previewColorB: Long,
) {
    LATENT("latent", "潜影 · Latent", "WebGL 流体极光网格，随情绪与节拍流动", 0xFF1E3A8A, 0xFF38BDF8),
    COMMON("common", "弥散 · Common", "专辑封面柔焦弥散与呼吸光晕", 0xFF312E81, 0xFFA78BFA),
    MONET("monet", "莫奈 · Monet", "印象派柔彩笔触与光影慢漂", 0xFF0F766E, 0xFFF59E0B),
    NOMAND("nomand", "星野 · Nomand", "深空星尘穿梭与微光星云", 0xFF0F172A, 0xFF60A5FA),
    SORA("sora", "晴空 · Sora", "通透天际霞光梯度与柔云流动", 0xFF0284C7, 0xFFFB7185),
    PURE("pure", "极简 · Pure", "深邃纯黑底幕，聚焦文字最省性能", 0xFF090D16, 0xFF1E293B),
}

/** AI theme mode / curated atmosphere presets for the lyric stage. */
@Serializable
enum class FoliaAiPalette(
    val label: String,
    val desc: String,
    val accentArgb: Long,
    val brightArgb: Long,
    val orb1Argb: Long,
    val orb2Argb: Long,
) {
    AUTO("AI 随歌智能", "自动分析封面主色与歌词情绪生成专属主题", 0xFF38BDF8, 0xFF7DD3FC, 0xFF1E3A8A, 0xFF312E81),
    CYBER("赛博霓虹", "电光品红与青蓝碰撞，适合电子/摇滚", 0xFFF43F5E, 0xFF38BDF8, 0xFF4C0519, 0xFF0C4A6E),
    JAZZ("午夜爵士", "暖琥珀金与深海蓝，复古温暖氛围", 0xFFF59E0B, 0xFFFDE68A, 0xFF451A03, 0xFF1E1B4B),
    SUNSET("落日余晖", "珊瑚橘红与暮紫霞光，温柔浪漫", 0xFFFB7185, 0xFFFDBA74, 0xFF7C2D12, 0xFF581C87),
    SAKURA("京都樱雨", "淡粉樱花与夜空靛蓝，治愈抒情", 0xFFF472B6, 0xFFFBCFE8, 0xFF500724, 0xFF1E3A8A),
    AURORA("极光冰川", "翡翠青碧与极地天青，空灵通透", 0xFF2DD4BF, 0xFF99F6E4, 0xFF042F2E, 0xFF0C4A6E),
    NOIR("黑金胶片", "香槟金与碳黑胶片，高级电影质感", 0xFFEAB308, 0xFFFEF08A, 0xFF1C1917, 0xFF292524),
}

/** WebView stage rendering resolution scale to tune TV smoothness vs sharpness. */
@Serializable
enum class FoliaRenderQuality(val label: String, val scale: Float) {
    PERFORMANCE("0.5× 性能优先", 0.5f),
    BALANCED("0.75× 均衡推荐", 0.75f),
    ULTRA("1.0× 原生清晰", 1.0f),
}

/** Folia typography family style. */
@Serializable
enum class FoliaFontStyle(val id: String, val label: String) {
    SANS("sans", "无衬线 · 现代"),
    SERIF("serif", "衬线 · 诗意"),
    MONO("mono", "等宽 · 机械"),
}

/** Folia animation intensity preset. */
@Serializable
enum class FoliaAnimIntensity(val id: String, val label: String) {
    CALM("calm", "舒缓"),
    NORMAL("normal", "标准"),
    CHAOTIC("chaotic", "狂放"),
}

/** Folia lyric font size scale. */
@Serializable
enum class FoliaFontScale(val label: String, val scale: Float) {
    SMALL("紧凑 0.85×", 0.85f),
    NORMAL("标准 1.0×", 1.0f),
    LARGE("放大 1.15×", 1.15f),
    XLARGE("巨幕 1.3×", 1.30f),
}

@Serializable
data class Settings(
    val preferredQuality: Quality = Quality.MASTER,
    val fallbackEnabled: Boolean = true,
    /** Audio offload (DSP direct path / HDMI passthrough). Bit-perfect Hi-Res on good HALs,
     *  but some vendor HALs (小米/鸿蒙/坚果 etc.) crash natively in the offload path — the
     *  settings toggle lets affected users turn it off. Default on so existing users keep
     *  the current behaviour. */
    val audioOffloadEnabled: Boolean = true,
    /** 强制软件解码（音频）。部分电视、盒子的硬件解码器会闪退或没声音，开启后只用系统软件解码器，
     *  同时关闭硬件直通。默认关闭。 */
    val forceSoftwareDecoding: Boolean = false,
    val showLyricTranslation: Boolean = true,
    val lyricSize: LyricSize = LyricSize.STANDARD,
    val lyricStyle: LyricStyle = LyricStyle.APPLE,
    val vinylColor: VinylColor = VinylColor.BLACK,
    val themeColor: ThemeColor = ThemeColor.BLUE,
    /** AI / Smart ambient theme: dynamically adapts player backdrop, waveform, and lyric glow
     *  to each song's cover palette and lyric mood. */
    val aiThemeEnabled: Boolean = true,
    /** Experimental Plan B: WebView WebGL/Canvas GPU lyric stage reproducing all 18 Folia templates.
     *  Off by default; requires explicit opt-in from Settings due to high GPU/RAM requirements. */
    val foliaWebViewEnabled: Boolean = false,
    val foliaTemplate: FoliaTemplate = FoliaTemplate.FUME,
    val foliaRandomTemplate: Boolean = false,
    val foliaBackground: FoliaBackground = FoliaBackground.LATENT,
    val foliaRandomBackground: Boolean = false,
    val foliaAiPalette: FoliaAiPalette = FoliaAiPalette.AUTO,
    val foliaBeatReactive: Boolean = true,
    val foliaGlowEnabled: Boolean = true,
    val foliaFilmGrain: Boolean = true,
    val foliaRenderQuality: FoliaRenderQuality = FoliaRenderQuality.BALANCED,
    /** Bottom translation subtitle overlay in Folia stage — OFF by default so no box covers the bottom stage. */
    val foliaShowBottomSubtitle: Boolean = false,
    val foliaDaylight: Boolean = false,
    val foliaBgStatic: Boolean = false,
    val foliaFontStyle: FoliaFontStyle = FoliaFontStyle.SANS,
    val foliaAnimIntensity: FoliaAnimIntensity = FoliaAnimIntensity.NORMAL,
    val foliaFontScale: FoliaFontScale = FoliaFontScale.NORMAL,
    /** Player screen: hide progress bar + transport after a few idle seconds; any D-pad / OK key
     *  brings them back. Off by default. */
    val autoHidePlayerControls: Boolean = false,
    /** 显示调试信息: app-wide switch for diagnostic UI (off by default). Currently: the player's
     *  status line (正在获取播放地址 / 播放失败已跳过 / 已换音源 …). Gate future debug UI on it too. */
    val showDebugInfo: Boolean = false,
    /** Absolute path of the chosen download root (a writable storage volume). null ⇒ default
     *  app-scoped Music dir on primary storage. See DownloadStore.availableRoots(). */
    val customDownloadDir: String? = null,
    /** A user-picked SAF folder (content tree Uri) to download into. When non-null it takes
     *  precedence over [customDownloadDir] — files are exported into this folder so they live in
     *  a browsable location (e.g. a USB drive folder). null ⇒ use the File-based volume root. */
    val customDownloadTreeUri: String? = null,
    /** Max simultaneous download transfers (batch or otherwise). Clamped to 1..8 on apply. */
    val maxConcurrentDownloads: Int = 3,
    /** Batch "下载全部": when a track is already downloaded but at a different quality than the
     *  chosen target, re-download it to match (true) vs. always skip already-downloaded (false). */
    val redownloadOnQualityChange: Boolean = true,
    /** Sources that feed the discover (home) page. Spec docs/discover-page-spec-android-tv.md §2:
     *  fixed 4 in order kw → wy → kg → tx, default all enabled. */
    val homeSources: Set<com.walkman.tv.data.model.SourceID> = setOf(
        com.walkman.tv.data.model.SourceID.KW,
        com.walkman.tv.data.model.SourceID.WY,
        com.walkman.tv.data.model.SourceID.KG,
        com.walkman.tv.data.model.SourceID.TX,
    ),
)

/** App preferences persisted as JSON. */
class SettingsStore(context: Context) {
    private val store = JsonStore(
        File(context.filesDir, "settings.json"),
        Settings.serializer(),
        Settings(),
    )

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    suspend fun loadAll() {
        _settings.value = withContext(Dispatchers.IO) { store.load() }
    }

    suspend fun update(transform: (Settings) -> Settings) {
        _settings.value = transform(_settings.value)
        withContext(Dispatchers.IO) { store.save(_settings.value) }
    }
}
