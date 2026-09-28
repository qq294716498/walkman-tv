# 好听-TV

基于 [SincereXing/walkman-tv（原版 WALKMAN）](https://github.com/SincereXing/walkman-tv) 修改的 Android TV 音乐播放器。本仓库保留原版的 Kotlin、Jetpack Compose for TV 架构与遥控器交互，并加入多账号音乐内容、推荐页分层、语音搜索、应用内更新和电视品牌图标。感谢原作者和原项目贡献者；具体改动见[本版新增内容](#本版新增内容)。

本项目采用 [Apache License 2.0](LICENSE)。它与原版使用不同的应用 ID（`com.walkman.tv.autosource`），可同时安装；两者的账号、歌单和设置数据不会自动互通。

> **使用前先看：**部分在线歌曲需要在「设置 → 自定义音源」导入兼容 lx-music v4 协议的脚本才能解析播放地址。应用不提供音源脚本或版权音频；各平台接口、登录和语音识别的可用性取决于平台与电视系统。

## 下载与安装

在 [Releases](https://github.com/qq294716498/walkman-tv/releases/latest) 下载适合电视的 APK：

| 文件名中的标记 | 适用设备 |
| --- | --- |
| `universal` | 不确定处理器类型时选择；包含全部 ABI |
| `arm64-v8a` | 大多数 64 位 ARM 电视或盒子 |
| `armeabi-v7a` | 32 位 ARM 设备 |
| `x86_64` | x86 设备或 Android TV 模拟器 |

正式发布文件名形如 `好听-TV-1.4.0-universal-release.apk`。系统最低要求 Android 6.0（API 23）。可通过电视文件管理器从 U 盘安装，或使用 `adb install`。升级时保留相同应用 ID 和签名即可覆盖安装；如果电视提示签名不一致，先备份本版数据，再决定是否卸载旧包。

应用内「设置 → 检查更新」读取**本仓库**的最新 GitHub Release，并选择与设备 ABI 匹配的 APK。下载和安装需要电视能够访问 GitHub、授予安装未知应用权限。测试构建可在 [GitHub Actions](https://github.com/qq294716498/walkman-tv/actions) 下载，发布版以 Releases 为准。

## 本版新增内容

- **推荐页分层：**「公开推荐」保留原版的推荐歌单、排行榜、左侧正在播放区，以及酷我、酷狗、QQ 音乐、网易云等已启用平台；「账号音乐」集中显示登录后的内容。顶部主导航结构保持不变。
- **账号歌单管理：**支持多个网易云与 QQ 音乐账号；按「全部 / 网易云收藏 / QQ 音乐」和具体账号筛选。每页默认展示 12 个紧凑歌单卡片，需要时展开全部；账号添加、切换和移除入口位于账号页底部。网易云收藏依据接口的收藏标记筛选。QQ 接口未返回的歌单不显示。
- **网易云个性化：**账号连接后可使用私人 FM、心动模式及为你推荐的歌单；支持扫码或中国大陆 +86 手机号验证码登录。若平台要求更换登录方式，以平台实际提示为准。
- **QQ 音乐账号：**支持扫码登录和账号歌单。QQ 登录不会替换原版的公开 QQ 搜索、排行榜和歌单功能。
- **搜索语音输入：**在原有遥控器键盘与手机扫码输入之外加入语音搜索。优先请求电视内置麦克风（支持的 Android 版本上），识别后沿用原有多平台搜索；电视必须向第三方应用提供系统语音识别服务，只有内置语音助手并不保证可用。
- **品牌与更新：**应用内名称统一为「好听-TV」，新增带透明背景的 16:9 电视启动横幅；保留方形应用图标。应用内更新指向本仓库，并支持从本仓库自动获取 LX 音源提示地址的更新。

[查看电视横版图标](app/src/main/res/drawable-nodpi/haoting_tv_banner.png)。

## 原版功能保留

| 区域 | 功能 |
| --- | --- |
| 推荐与平台浏览 | 公开推荐、排行榜、歌单广场；可在设置中选择发现页平台 |
| 搜索 | 酷我、酷狗、QQ 音乐、网易云等平台聚合搜索与筛选；遥控器键盘、手机扫码、语音输入 |
| 播放器 | Media3 后台播放、全屏封面、逐行歌词、波形与播放控制、MV |
| 我的列表 | 收藏、播放历史、自建歌单、本地持久化 |
| 歌单导入 | 从网易云、QQ 音乐、酷狗、酷我分享链接导入在线歌单 |
| 下载与本地音乐 | 单曲和整单下载、音质选择、下载目录、音频标签；扫描并导入本地音乐 |
| 自定义音源 | 通过 URL、文件、脚本内容或手机扫码导入 lx-music v4 兼容 JS 脚本 |

**酷狗只是不参与新增的账号登录体系；原版酷狗公开功能仍保留。** 平台目录和播放接口可能随平台调整而变化；账号歌单、封面和在线歌曲以实际可用结果为准。

### 音质与音源

应用按所选目标音质尝试获取可用版本，并在无法获得目标音质时逐级降级。支持 `master`、`atmos_plus`、`atmos`、`hires`、`flac24bit`、`flac`、`320k`、`128k` 等档位；实际可播放音质取决于平台、账号权限和所用自定义脚本。

自定义源兼容 [洛雪音乐 lx-music](https://github.com/lyswhut/lx-music-mobile) v4 用户脚本协议。脚本主要用于播放地址与歌词解析；搜索、排行榜和歌单目录由平台接口提供。请仅使用有权访问的内容和可信脚本。

## 基本操作

1. 安装并打开应用，使用遥控器方向键移动焦点，按 OK 选择，按返回键关闭当前页面或弹层。支持的页面也可点击操作。
2. 若歌曲无法解析，在「设置 → 自定义音源」导入自己的兼容脚本，按需调整播放音质和发现页平台。
3. 在「推荐 → 账号音乐」底部进入账号管理，添加 QQ 音乐或网易云账号。返回账号音乐页后选择平台、账号与歌单。
4. 搜索时可使用电视键盘、语音按钮或手机扫码。扫码输入要求手机与电视处于可互访的局域网。

账号登录凭据保存在电视应用的本地数据中，不写入 Git 仓库；移除账号后将从应用中删除相应会话。请勿在公开 Issue 或截图中粘贴 Cookie、验证码或完整二维码。

## 技术与构建

- Kotlin、Jetpack Compose for TV、Media3 ExoPlayer / Session
- OkHttp、Coroutines / StateFlow、Coil
- QuickJS 运行自定义源脚本；NanoHTTPD 与 ZXing 提供手机扫码输入
- Android SDK（`compileSdk 36`、`minSdk 23`、`targetSdk 34`）及 JDK 17

在配置好 Android SDK 的构建环境中运行：

```bash
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease
```

输出位于 `app/build/outputs/apk/`。Debug APK 使用仓库已有的调试签名以便测试。Release 工作流在推送 `v*` 标签后构建并上传四种 APK；如未配置仓库的 Release 签名密钥，现有工作流会回退到调试签名。需要正式分发签名时，请先配置 `RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。

主要代码位于 `app/src/main/java/com/walkman/tv/`：`cloud/` 处理 QQ/网易云账号，`source/` 处理平台目录与音源脚本，`playback/` 处理播放、下载和更新，`ui/` 为 TV 页面与焦点交互。自动构建见 [Debug workflow](.github/workflows/debug.yml) 与 [Release workflow](.github/workflows/release.yml)。

## 来源、许可与声明

本项目是从 **[SincereXing/walkman-tv](https://github.com/SincereXing/walkman-tv)** 派生的修改版，保留原版的 Android TV 播放器基础功能和 Apache-2.0 许可。本版增加的账号、推荐页、语音、品牌与更新功能由本仓库维护；问题与更新请在本仓库反馈。感谢 [洛雪音乐 lx-music](https://github.com/lyswhut/lx-music-mobile) 的脚本协议与社区生态。

本项目与 QQ 音乐、网易云音乐、酷狗音乐、酷我音乐及原版作者均无官方合作关系。应用不内置或提供版权音乐内容，请遵守相关服务条款并支持正版。
