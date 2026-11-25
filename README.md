# 小黑桌宠 · Android 移植版

把 Windows 版《小黑桌宠》（Electron）的**渲染层原样搬进 Android WebView**，用一个透明置顶悬浮窗把它托在桌面上。
立绘、动作、表情、状态机全部还是它自己的代码在跑，Android 这边只补宿主环境（窗口、触摸、存档、菜单）。

- 目标设备：S100V8H（Android 9 / 1280×800 / density 160）
- 包名：`com.deepfish.xiaohei`
- 形态：少年（`h1b/h1w` 分层立绘）+ 黑猫（`v2tv` 整图），两套都在
- 当前版本：**1.0**（`v1.0`），仓库 <https://github.com/coolsmile1026/xiaohei-pet-android>，支持 OTA 自更新（见第十章）

---

## 一、为什么是「WebView + 悬浮窗」

原版是 `透明窗口 + 鼠标穿透 + 主进程 IPC`。安卓没有等价物，拆成两层：

| 原版 | 这里 |
| --- | --- |
| BrowserWindow（transparent / alwaysOnTop / click-through） | `TYPE_APPLICATION_OVERLAY` 悬浮窗，`FLAG_NOT_TOUCHABLE`（铺满整屏但不接触摸，点击全透给下层应用） |
| 主进程全局鼠标钩子判定「点到宠物了」 | 独立的 1px 命中区小窗，跟着 `pet-box` 移动，只盖住宠物本体 |
| 渲染进程的鼠标事件 | 命中区把 `MotionEvent` 经 `evaluateJavascript` 合成成 `mousedown/mousemove/mouseup` 回灌页面 |
| `require('fs')` 同步读 rig.json | 构建期把 35 个 JSON 内联成 `preload-data.js` 的 `window.__FILES` |
| `ipcRenderer.invoke('load')` 读存档 | JS 接口 `XiaoheiHost.loadState()`（SharedPreferences） |
| 主进程 `openMenu2()`：`model.build()` + `send('menu2',{t:'open'})` | 原版 `menu2` 照跑：`model.build()` 在页面里执行（`tools/wrap-cjs.js` 包的 CommonJS 壳），面板是原版的 DOM 气泡布局 |
| 主进程 `popupWindow({file:'about.html'})` 另开窗口 | 第二个悬浮窗装 WebView，加载同一张 `about.html`；页面缺的那半边主进程由 `pageshim.js` 顶上 |
| `win.setPosition` 拖动窗口 | 不需要——宠物在画布内移动，页面自己处理拖动 |

关键点：**没有改一行原版渲染层代码**。所有适配都在 `shim.js` 和 Kotlin 宿主里。

---

## 二、目录

```
.
├── app/src/main/
│   ├── assets/web/
│   │   ├── groups.default.json # 群二维码（作者带的默认数据，页面 fetch 进来）
│   │   ├── renderer/          # 原版渲染层 32 个 JS + index.html（仅注入 viewport/preload-data/shim）
│   │   │   ├── shim.js        # require / process / ipcRenderer 兼容层 + __emit / __synth / __saveNow
│   │   │   ├── preload-data.js# 生成物：assets 下 35 个 JSON 内联成 window.__FILES（tools/build-data.js）
│   │   │   ├── pageshim.js    # 小窗口的页面侧垫片：假 ipcRenderer + 本地数据 + OTA 状态/动作桥
│   │   │   ├── about.html     # 「关于」：版本 / 更新 / 作者 / 版权
│   │   │   ├── howto.html     # 「怎么玩」：7 条手势 + 作者（原版 welcome.html 的视频教程没法发）
│   │   │   ├── group.html     # 「进群」：群二维码 + 群号复制
│   │   │   ├── face.html      # 「大脸猫」：整屏一张重画的猫脸（黑底）
│   │   │   ├── face.js        # 矢量画两眼（按整屏尺寸），无鼻/嘴/轮廓/耳朵；14 表情洗牌袋渐变+红心心跳+瞳孔跟随；2x 超采样消锯齿
│   │   │   └── menu2/         # 原版菜单：menu2.js/css + model.js（含 model.cjs.js 浏览器壳）
│   │   └── assets/            # 解密后的 PNG + 未加密的 sound/tutorial/json
│   └── java/com/deepfish/xiaohei/
│       ├── PetService.kt      # 舞台 + 命中区 + 小窗口 + 桥接（核心）；前台服务 + 5 分钟心跳保活
│       ├── PetMenu.kt         # 原生兜底菜单（menu2 起不来时才用）
│       ├── PetLauncherActivity.kt # 控制页：召唤 / 收起 / 切换形态 / 权限 / 常驻设置
│       ├── PetPrefs.kt        # 存档
│       ├── DockScan.kt        # 无障碍识别结果的跨进程共享文件
│       ├── DockWatchService.kt# AccessibilityService：真识别 Aerial dock 图标
│       ├── OtaUpdater.kt      # OTA：版本比较 / 多线路竞速 / 下载校验 / 安装
│       ├── ApkProvider.kt     # 把下载好的 APK 以 content:// 交给系统安装器
│       └── BootReceiver.kt    # 开机 / 解锁 / 更新后自启，含「用户主动收起」开关判断
├── update.json                # 自托管 OTA manifest（GitHub Releases API 的备用通道）
└── tools/                     # 构建期 / 发版脚本，不参与打包
    ├── build-data.js          # assets/**/*.json → renderer/preload-data.js
    ├── decrypt-assets.js      # 原版 XHA1 封包 PNG → assets/
    ├── wrap-cjs.js            # menu2/model.js → 浏览器能跑的 model.cjs.js
    └── release.sh             # 一键发版：编译 + 打 tag + 建 Release + 传附件
```

Kotlin + 宿主侧 JS 约 1050 行，`tools/` 三个构建脚本共 288 行。`shots/` 是真机验证截图（仓库只留 README 引用的几张）。

---

## 三、构建

### 前置

| 项 | 值 |
| --- | --- |
| 编译 SDK | 34（`compileSdk = 34`） |
| 运行下限 | `minSdk = 26`（Android 8.0），目标机是 9 |
| ABI | 只有 `armeabi-v7a` |
| 依赖 | 零第三方库，纯 SDK + Kotlin 标准库 |
| SDK 路径 | `local.properties` 里的 `sdk.dir` |

### 只改代码（日常）

`assets/` 与 `preload-data.js` 都已经提交，克隆下来就能直接编：

```bash
gradle assembleDebug --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`--offline` 只是为了不联网拉 AGP，本地缓存里都有；换机器删掉即可。仓库没有 `gradlew`，用 Android Studio 打开直接 Build 也一样。

### 要换/补原版素材时

有一步是**别人的仓库里没有的**：`assets/` 下的 PNG 在原版安装包中是加密的，`renderer/preload-data.js` 是生成物。两个脚本都在 `tools/`，都做过产物比对（213 个 PNG 逐字节一致、35 个 JSON 键值一致），不是「大概能跑」。

**1. 解包原版**

```bash
npx asar extract resources/app.asar app-unpacked
```

`resources/app.asar.unpacked` 保持原样别动——加密资源（`assets/v2`、`assets/v2tv`、`assets/settings` 三处的 PNG）和密钥封包 `assets/k.bin` 都在这里。

**2. 解出 PNG**

```bash
node tools/decrypt-assets.js \
  --app  app-unpacked \
  --asar resources/app.asar.unpacked \
  --out  app/src/main/assets/web/assets
```

脚本会递归扫子目录——`v2tv` 的 162 张里有 134 张在 `anim/` `stow/` `jump/` `meal/` 下面，只扫顶层会静默漏掉一大半（文件数少一截，但不会报错，很容易忽略过去）。

密钥不是常量，是两段式算出来的：

```
codeKey = sha256('xhwrap1\0' + package.json#main + 按字典序拼接的每个代码文件)
master  = AES-256-GCM(codeKey, assets/k.bin)      // 12B iv + 16B tag + 32B 密文
png     = AES-256-GCM(master, 'XHA1' + 12B iv + 16B tag + 密文)
```

所以换了版本、代码指纹变了也能自动对上；反过来，`--app` 和 `--asar` 必须来自同一份安装包，否则解封 `k.bin` 直接失败——脚本会明确报这句，不会静默产出坏图。

`assets/sound/`、`assets/tutorial/`、`assets/video/*.json`、`groups.default.json` 本来就没加密，直接拷。

**音效：原版就带声音，已全量同步**

安装包 `resources/app.asar.unpacked/assets/sound/` 下共 15 个文件 = 14 个音频 + 1 份 `fx/来源.md`（Freesound CC0 授权与剪辑清单），**完全没有 XHA1 加密**：

| 分组 | 文件 | 用途 |
| --- | --- | --- |
| `cat/` | `cat_meow` `cat_surprise` `cat_tilt` `cat_yawn`（.ogg） | 喵、咦、歪头、哈欠 |
| `heixiu/` | `hx_talk1` `hx_talk2` `hx_xiu`（.ogg） | 嘿咻的三段叫声 |
| `fx/` | `fx_eat` `fx_chew` `fx_lap` `fx_land` `fx_tea_track`（.ogg） | 吃、嚼、舔、落地、喝奶茶整段（14.4s） |
| `fx/` | `fx_purr.wav` `fx_snore.wav`（PCM 44.1k 单声道） | 呼噜、打呼，两个无缝循环 |

播放链路：`renderer/sound.js` 用 `new Audio('../assets/sound/…')` 播一次性音效、用 `fetch` + `AudioContext.decodeAudioData` 跑循环音效；页面挂在 `http://pet.local/renderer/`，`../assets/sound/` 解析成 `http://pet.local/assets/sound/`，由 `PetService` 映射到 `assets/web/assets/sound/`。`WebSettings.mediaPlaybackRequiresUserGesture = false`，动作触发即可出声，不需要先点一下解锁。`window.__FILES` 只内联 JSON，音频不走这张索引表。

**声音什么时候响**：不是背景音乐，全靠动作触发。`Sound.CUES` 把音效挂在动作时间线上——`body_meowc` 喵、`body_yawn` 哈欠、`body_tilt` 歪头、`surprise_body` 咦、`eat` 吃/嚼、`eat_end` 舔；睡觉走 `fx_snore` 循环、摸头走 `fx_purr` 循环，喝奶茶是独立的 `fx_tea_track` 整段（14.4 秒），嘿咻说话是 `hx_talk1/2`、`hx_xiu`。猫自己闲着的时候本来就不会一直有声音。

**之前为什么一声不响（已修）**：原版由 Electron 主进程下发完整 settings，移植版 `PetService.evalSettings()` 只推了 `bodyScale`，于是 `Sound.S.soundOn` 一直保持 `sound.js` 里的初始值 `false`。真机日志是 `[sound] cat_meow muted (off)`——每次 `play()` 都在门控处返回 `false`，音频对象、URL、解码全都正常（`fetch` 返回 `200 audio/ogg`，`canPlayType('audio/ogg; codecs=vorbis')` = `probably`）。所以是「触发了但被总开关挡住」，不是没触发、也不是文件缺失。

修复：`evalSettings()` 现在单独调 `Sound.setSettings({soundOn, volume, soundCats:{}})`（不塞进 `__emit('settings')`，避免连带触发 `Scene`/`Pet` 的 applySettings）；开关状态存 `files/pet_sound.off`、音量档位存 `files/pet_volume.level`，和自启标志一样走文件而不是 SharedPreferences——服务在 `:pet` 独立进程，SP 跨进程读不到最新值。控制页新增「音效：开 / 关」按钮，点了立刻通过 `ACTION_SETTINGS` 让服务重新下发，不用等重启。（早期为了让轻点听得见，曾把 `BANK` 的 `mix` 放大 1.7 倍，后来发现低档位被抬得太高，已撤回，见「控制页的小猫音量调节」。）

**第二次排查：开关开了、点了还是听不到（已修）**：真机日志证明点击其实在播——`act tilt` → 约 1.5 秒后 `SNDPLAY cat_tilt ret=true`，系统 `requestAudioFocus(USAGE_MEDIA)` 成功、`AudioHardwareTiny set_spk_ctl spk_on:1`。问题另有两处：

1. 音量只有 `vol 0.30`（原版 `volume:60 × mix:0.5`），再乘系统媒体音量就几乎听不见 → 当时把 mix 抬到 0.85，后来为了低档位手感又撤回了（见「控制页的小猫音量调节」）。
2. 原版只有少数动作挂了 CUE，`click -> busy` / `click -> wake` 这类点击根本不发音，带 CUE 的还要等 1.5 秒；「切换形态」更是从来没挂音效。拿这两个操作当测试，自然觉得没声音。

于是补了交互反馈：`relayTouch` 给「真·轻点」（短按、无拖动、2 秒节流，不干扰长按和拖拽）直接补一声即时 `cat_meow`；控制页「切换形态」通过新增的 `ACTION_SOUND` / `PetService.playSound()` 也补一声。点完立刻有声，动作本身的 CUE 稍后可能再响一声，属正常叠加。

**顺带修掉循环音效**：`fx_purr`（呼噜）/ `fx_snore`（打呼）走的是 `Sound._loopBuf` 里的 `require('fs').readFileSync`，而 `shim.js` 的假 `fs` 只查 `window.__FILES`（只内联了 JSON），日志一直是 `fs miss /app/assets/sound/fx/fx_snore.wav -> ENOENT`。`evalSettings()` 里把 `_loopBuf` 换成 `fetch` + `AudioContext.decodeAudioData` 的浏览器路径后，真机日志变为 `[sound] fx_snore loop on vol 0.85` / `loop off`。

真机验证汇总：启动后 `Sound.S.soundOn=true`；点静音变 `false` 且日志回到 `muted (off)`，再点开恢复；轻点猫 `[sound] cat_meow vol 0.85` + `spk_on: 1`；切换形态同样一声；睡觉/醒来 `fx_snore loop on/off`。

**控制页的小猫音量调节**：紧挨「音效：开 / 关」下面一行是 `− 小猫音量：20%（点此试听） ＋`。点击 −/＋ 按 **5 一档**在 0..100 之间调，默认 **60**（原版默认值），中间数字可点试听，调档后也会自动试听一声 `cat_meow`（连续点只试听最后一声，避免叠成一串）；静音时调档只存值、不试听并提示先开启。档位写进 `files/pet_volume.level`，`ACTION_SETTINGS` 立刻重发 settings；`sound.js` 的 `setSettings` 会重算正在播放的一次性音效音量与循环音效的 gain，所以连打呼/呼噜都会跟着变，正在播的声音也即时生效。

> **这是 App 内的音效音量，不是系统媒体音量**：代码里没有任何 `AudioManager.setStreamVolume` 调用，电视的系统音量不会被改。
> 之前为了轻点能听见，运行时把 `BANK` 每条 `mix` 乘了 1.7（猫叫 0.5→0.85），结果低档位整体被抬高一截——
> 反馈是「10% 就有点大、5% 又太小，过渡不平滑」。现在这个放大已撤回，回到原版 `mix`（0.5），
> 音量按 0..100 线性下发，步进从 10 改成 5。真机实测 20% → `cat_meow vol 0.10`，25% → `0.13`；档位重启后保持。

顺带修了一个会挡住按钮的坑：宠物悬浮命中区（`hitView`）盖在控制页上方时，右侧按钮的点击会被宠物吃掉，日志是 `touch down` 而不是按钮点击。控制页 `onResume` 通过 `ACTION_FRONT` 让服务把命中区临时移出屏幕（`applyBoxZone()` 里 `controlFront` 分支），`onPause` 再摆回宠物身上；真机确认前台时窗口在 `(-10000,-10000)`、退出后回到 `(1004,311)`，点猫照常触发。

**形态与音色**：音效按「动作片段」触发，不按人 / 猫形态区分。`Sound.CUES` 只有 7 组 key（`body_smile` / `body_meowc` / `body_yawn` / `body_tilt` / `surprise_body` / `eat` / `eat_end`），挂的全是 `cat_meow` / `cat_tilt` / `cat_yawn` / `cat_surprise` 猫叫和 `fx_eat` / `fx_chew` / `fx_lap` 动作音。`Sound.CATS` 里虽然有「人形的声音」(`human`) 这个分类，但 `BANK` 的 `cat` 值只有 `cat` / `hx` / `fx` 三种，**没有任何一条音效属于 human**——原版这个分类就是空的。所以人形态点它、歪头、打哈欠同样会喵，这是原版设定，不是移植丢了文件。唯一另一套「人声」是嘿咻说话时 `Heixiu.HOP_SAY` 的 `hx_talk1` / `hx_talk2` / `hx_xiu`；`Heixiu.catVoiceBusy()` 还会在猫叫正忙时压住说话声，避免两种声音叠在一起。另外 Android 端补的两处即时反馈（轻点宠物、控制页「切换形态」按钮）也无条件用 `cat_meow`，与当前形态无关。真机确认：人形态点击 → `click -> mood` + `[sound] cat_meow`。

> 原版 `settings.html` 里那四个音量分类（猫叫 / 人形 / 嘿咻 / 动作音效）在 Android 端仍没有入口——`openPage()` 只接 about / howto / group。控制页现在提供的是总开关 + 总音量档位；要按分类细调，得给设置页加入口并把 `soundCats` 同步回舞台页（注意 `human` 分类本来就没有音效可调）。

已逐项核对：exe 的 unpacked 目录 ↔ 仓库 `app/src/main/assets/web/assets/sound/` 共 15/15 个文件，SHA-256 逐个一致、无缺无多；`app.asar` 内部另有一份同内容副本，安装包里没有其它音频；当前 `app-debug.apk` 也已把 15 个文件全部打进包内。研究快照 `xiaohei-unpacked/assets/sound/` 同步补齐了同一份。

**3. 重新内联 JSON**

```bash
node tools/build-data.js
```

把 `assets/` 下 35 个 JSON 的原文塞进 `renderer/preload-data.js` 的 `window.__FILES`，键是相对 `assets/` 的路径。原版渲染层用 `require('fs').readFileSync` 同步读这些文件，WebView 里没有同步 IO，`shim.js` 的假 `fs` 就按这张表查。脚本会先 `JSON.parse` 校验，宁可停下也不生成坏文件。

**4. 再编译安装**，回到上一步。

### dock 上沿：用无障碍服务真识别 Aerial dock 图标

**只针对 Aerial 屏保桌面（`app.orchard.tv`）**，不再考虑美好家智能桌面（8 图标常驻 dock）。上一版的「按屏高比例标定 + 两套桌面预设」已完全撤掉。

**识别原理**：`DockWatchService` 是一个 `AccessibilityService`，`packageNames=app.orchard.tv`，只读节点树、不点击、不输入、不读其它应用内容。它遍历 `rootInActiveWindow`：

- dock 显示时是底部一排 5 个 `android.view.View`（focusable），实测 bounds `[75,586][271,696]`，下面各有 TextView 标签（云视听小电视 / VidHub / 天翼超高清 / Marco Movie / 当贝市场）；当前有焦点那个会被 Compose 合并成稍大一圈的 `[527,578][753,704]`。
- 应用列表是同样规格的 3 行图标（top ≈ 119 / 296 / 473）。所以按「图标行数 ≥ 2」判为 `apps`，「唯一一行且 top > 0.55×屏高」判为 `dock`，取该行 top 的**中位数**（避免焦点框放大 8px 把上沿带偏）。
- 标签只收图标正下方、长度 ≤ 24、宽度 ≤ 320、水平对齐的短文本；屏保壁纸的版权长文本会被 `flagIncludeNotImportantViews` 带进树里，必须排掉。

事件驱动 + 1.5s 轮询兜底，状态/图标顶变化时才通知舞台。结果写 `files/pet_dock.scan`（JSON：`state / iconTop / count / labels / at`，临时文件 + rename 防读半截）。无障碍服务与舞台同在 `:pet` 进程，直接发 `ACTION_DOCK`；控制页在主进程，只读同一个 filesDir 文件，不用 SharedPreferences。

**落脚位置**：`图标顶 + 落脚微调 = 宠物脚底目标 y`。默认微调 `-26`，586 → 560，正好是 dock 半透明面板上沿。`−/＋` 每次 10px、范围 −80..80，存 `files/pet_dock.trim`，即时下发并连续缓动。

**为什么平移窗口、以及脚底真值**：`Pet.ground()` 走固定 perch，改 `Scene.ground()` 没用，所以原生层平移整个舞台窗口。窗口下移量 `v = ground - footPageY`，`footPageY` 取页面实际上报的 `pet-box` 底边（本机 754），`LayoutParams.y = 44 + stageY - v`。本机 `v = 560 - 754 = -194`（窗口上移 194px，`y=44→238`）。**上一版按屏高比例把脚底猜成 554，算出来只有 6px，猫其实还踩在 dock 下半截**——这版用真实包围盒修掉了。`stageOriginY` 同步变化，命中区跟着走，长按和拖动不受影响。

**三种形态都吸附**：v1.0 首个构建里 `applyDockOffset()` 带了 `!formCat -> 0` 的判断，只对 `Forms.isCat()` 生效，所以人形态和「派嘿咻出去」的黑球一直停在屏幕底部。现在这个判断已去掉——三种形态共用同一个舞台窗口，窗口平移对它们都生效；`footPageY` 直接取各自 `pet-box` 底边，形态切换时 `updateHitZone()` 发现底边变化（>3px）就立刻重算，不用为每种形态单独标定。截图：`shots/dock-form-{cat,human,heixiu}.png`。

**「爬」**：`ValueAnimator` 700ms 连续改窗口 y，不是闪现；服务每秒重算一次兜底，但动画正朝同一目标时不会重启（否则会一顿一顿）。

| 桌面状态 | 宠物行为（人 / 猫 / 嘿咻都一样） |
| --- | --- |
| dock 显示（识别到 5 图标） | 贴到真实图标顶算出的上沿 |
| dock 收起 / 控制页在前台 | 保持上次识别值，不上下抖 |
| 应用列表（3 行图标） | 爬回屏幕底部 |
| 关掉「贴 dock」开关 | 回屏幕底部 |

控制页「小猫音量」下面：

`[贴 dock：开]  [−]  [识别 5 图标 · 落脚 560px（点此重扫）]  [＋]` + `[无障碍识别：已开启（点此管理）]`

状态行会显示「dock 上沿：已识别 图标顶 586px → 落脚 560px（微调 -26）」和识别到的 5 个图标名。开关状态存 `files/pet_dock.off`（默认开）。

**授权**：普通手机上点按钮会打开系统无障碍设置；这台 TV ROM 裁掉了 `ACTION_ACCESSIBILITY_SETTINGS`（`pm resolve-activity` 返回 `No activity found`），所以按钮按「标准入口 → `com.android.tv.settings/.MainSettings` → 通用 `ACTION_SETTINGS`」依次回退。实测这台 ROM 的**系统设置 UI 里没有无障碍开关条目**（厂商定制设置页、设备偏好设置 → 高级设置都查过），需要用 adb 开启：

```bash
# 先看已有服务，别覆盖；把小黑追加到冒号分隔列表末尾
adb shell settings get secure enabled_accessibility_services
adb shell 'settings put secure enabled_accessibility_services <原有列表>:com.deepfish.xiaohei/.DockWatchService'
adb shell settings put secure accessibility_enabled 1
```

本机已开启（`adb shell dumpsys accessibility` 能看到「小黑 · dock 图标识别」）；卸载重装可能需要再来一次。没授权时控制页显示「未授权」：若之前已识别过，猫沿用上次真实识别值（不会突然掉下来），从未识别过则留在屏幕底部，不会乱猜一个高度。

真机验证（1280×800、density 160）：

| 操作 | 日志 | 结果 |
| --- | --- | --- |
| 呼出 dock | `state=dock iconTop=586 icons=5 labels=云视听小电视 / … / 当贝市场` | 真读到 5 个图标及名称 |
| 启动贴合 | `dock offset 0 -> -194 (ground=560, foot=754)`，舞台 `y=44→238` | 猫脚底 ≈556，正好站在面板上沿 |
| 按下键进应用列表 | `state=apps` → `dock offset -194 -> 0` | 连续爬回屏幕底部 |
| 返回键回 dock | `state=dock` → `dock offset 0 -> -194` | 连续爬回上沿 |
| ＋ / − 微调 | `trim=-36` → `-194 -> -204`；`trim=-26` → `-204 -> -194` | 即时缓动，值可恢复 |
| 切人形态 | `cmd toggleForm`，`offset` 保持 `-194` | 人直接站在上沿（`shots/dock-form-human.png`） |
| 派嘿咻出去 | `cmd heixiu out` → `act hxv` | 嘿咻在 dock 上沿跑动，不落到底部（`shots/dock-form-heixiu.png`） |

截图：`shots/dock-a11y-stand.png`（站立贴合）、`shots/dock-a11y-final.png`（最终包）、`shots/dock-a11y-control.png`（控制页识别状态）、`shots/dock-form-{cat,human,heixiu}.png`（三形态）。

### 卸载预装推广应用（AI健康管家 / 翼家智话）

这台 S100V8H 的系统分区预装了 `Yjjk`（AI健康管家，`com.to21cn.yjjk`）和 `Yjzh`（翼家智话，`com.chinatelecom.smartvoicebox`），桌面会露出两个图标，dock 第一格也被「AI健康管家」占着。只对当前用户卸载即可：

```bash
adb shell pm uninstall --user 0 com.to21cn.yjjk
adb shell pm uninstall --user 0 com.chinatelecom.smartvoicebox
```

两条都返回 `Success`，之后 `pm list packages` 里不再出现，Aerial 应用列表和 dock 标签里也没有了（dock 第一格变成「云视听小电视」）。APK 仍在 `/system/app/`，所以恢复出厂或系统 OTA 可能装回；要手动恢复：

```bash
adb shell cmd package install-existing com.to21cn.yjjk
adb shell cmd package install-existing com.chinatelecom.smartvoicebox
```

---

## 四、已真机验证

| 能力 | 验证方式 | 结论 |
| --- | --- | --- |
| 站姿 / 呼吸 / 眨眼 / 走动 / 睡觉 | 日志 `boot sleep` → `sleep -> wake` → `idle -> walk` | ✅ |
| 触摸穿透 | 舞台 `NOT_TOUCHABLE`；点设置页「网络设置」能跳转 | ✅ |
| 单击反应 | `click -> mood st=idle wait=393ms face=happy` | ✅ |
| 拖动 | `drag -> idle at 379,671`，角色跟手移动 | ✅ |
| 长按菜单 | 原版 `menu2` 气泡面板：人形 17 项 / 猫形 26 项，随形态变化 | ✅ |
| 菜单项点击 | 「变成猫」→ `cmd toggleForm`；「设置」→ 打开控制页；「退出小黑」→ 存盘并退出 | ✅ |
| 子菜单 | 「更多」展开网格面板（设置/怎么玩/进群/关于/退出小黑） | ✅ |
| 点空白关菜单 | 命中区自动收回宠物大小 | ✅ |
| 小窗口「关于」 | `menu shell about` → `about.html`：版本 / 更新 / 作者 / 版权，520×305 | ✅ |
| 小窗口「怎么玩」 | `menu shell howto` → `howto.html`：7 条手势 + 作者 + 版权，560×418 | ✅ |
| 小窗口「进群」 | `menu shell group` → `group.html`：群二维码 + 群号复制 + 作者，560×470 | ✅ |
| 三张卡片内容不重复 | 关于无群卡/无手势，怎么玩无群卡，进群只有群卡 | ✅ |
| 菜单「回主页」 | 原「找文件」位：`menu shell find` → `go home -> app.orchard.tv`，从设置页回到桌面 | ✅ |
| 复制群号 | 点「复制群号」→ `WRITE_CLIPBOARD` 放行，按钮变「已复制」 | ✅ |
| 小窗口关闭 | ×／「知道了」/ 页面内 `data-do=close` 都能关，命中区从 -10000 收回宠物大小 | ✅ |
| 形态切换 | `cmd toggleForm` → `humanb1` ⇄ `catb1` | ✅ |
| TV 动画帧（`v2tv/anim` `stow` `jump` `meal`） | 日志 `tvanim load body_lookback / body_yawn / work_up0 ok`（无 error），`idle -> walk at 840,754 catb1` 走路帧完整渲染 | ✅ |
| 动作执行 | 「打哈欠」`act yawn`、「歪头」`act tilt`、「闭眼喵」`act meowc`、「回头看」`act lookback`、「吹风」`act wind`、「睡觉」`yawn -> sleep` 并趴下 | ✅ |
| 动作重试 | 图集加载占住 solo 时首次被丢，`retry tilt #1` 后 `act tilt` 成功 | ✅ |
| 窗口类动作置灰 | 「上窗口」「趴着无聊」显示「暂无窗口」且不可点（`World.wins` 为空） | ✅ |
| 菜单外观适配 | 主圈贴纸胶囊随文字撑长（`fit stickers 3`），双击／Ctrl Ctrl／中键键帽全部隐藏 | ✅ |
| 大脸猫 | 菜单点「大脸猫」→ `menu shell hide` → `face open`，整屏矢量猫脸（只有两眼，尺寸按整屏算；无鼻/嘴/轮廓/耳朵）；好奇/生气/委屈/困/哈欠/晕/红心(带心跳)/单眼眨/微笑等表情，**任意两表情间连续渐变不闪切**（瞳孔形状+睁眼/弧眼+眼睑线+呼吸均连续量）；点一下从全部 14 个表情里洗牌发一个并停住（惊→目标，不再连闪；一轮内不重复、相邻不同组），90 秒不碰闭眼睡，瞳孔跟随+空闲游移；右下 × → `page closed`，桌面猫恢复 | ✅ |
| 存档 | `forms.form="human"`、`pet.x` 精确落盘 | ✅ |
| 杀进程恢复 | force-stop 后重启，形态 + 位置都回来 | ✅ |
| 开机自启 | `BOOT_COMPLETED` / `USER_UNLOCKED` / `MY_PACKAGE_REPLACED` / `BOOT_PROBE` → 无条件拉起前台服务（用户主动收起过才跳过）；真机用 `BOOT_PROBE` 等价验证：进程、心跳闹钟、ongoing 通知、桌面宠物全部起来 | ✅ |
| 常驻心跳 | `AlarmManager` 每 5 分钟 `RTC_WAKEUP`，PendingIntent 走 `getForegroundService` 豁免通道；`dumpsys alarm` 可见 `startForegroundService`，服务活着只续期 | ✅ |
| 省电白名单 | 控制页「常驻设置」状态行显示是否已加入；本机 TV ROM 的一键对话框是空 Stub，检测后改开应用详情页并 Toast 提示 | ✅ |
| 收起 | 先存盘再停服务，锁文件清理、心跳取消、写 `pet_stage.autoboot.off`；`dumpsys activity services` 为 `(nothing)`，再发探针日志是 `boot skip: user turned pet off` | ✅ |
| dock 图标真识别 | `AccessibilityService` 读 Aerial 节点树：`state=dock iconTop=586 icons=5` + 5 个图标名；猫 `offset 0 -> -194` 贴上面板，进应用列表 `-> 0`、返回 `-> -194`、人 ⇄ 猫自动归位 | ✅ |

### 开机自启与常驻

**原来根本不会自启**：`BootReceiver` 里先判 `PetService.isRunning()`（锁文件存在才恢复），
但开机时服务本来就没在跑，这个判断必然为 false，等于永远不会拉起。现在开机 /
`USER_UNLOCKED` / 应用更新 / `BOOT_PROBE` 都无条件 `show()`，只看两件事：悬浮窗权限，
以及用户有没有主动点过「收起」。

**用户主动收起要被尊重**：`autoBoot` 标志放在 `files/pet_stage.autoboot.off` ——
用文件而不是 SharedPreferences，因为服务跑在独立进程 `:pet`，SP 的进程内缓存跨进程
不可靠。点「召唤小黑」删文件（默认自启），点「收起」写文件关掉；开机和心跳都先查它。
`onCreate` / `onStartCommand` 也在写锁文件、建舞台之前拦一道，防止残留闹钟把服务拉回来。

**常驻靠三层**：

1. `START_STICKY` + `startForeground(1, …)` 的 ongoing 通知（`setOngoing(true)`，
   `IMPORTANCE_LOW`），系统回收后会重建服务；
2. `AlarmManager` 每 5 分钟一个 `RTC_WAKEUP` 精确闹钟，PendingIntent 用
   `getForegroundService`（API 26+ 后台拉起前台服务的豁免通道）；服务活着只是续期，
   被 ROM 杀掉就到点拉回来，`onStartCommand` 每次续下一次；
3. 省电白名单：控制页「常驻设置」一键申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
   **本机这台 TV ROM 把这个 action 解析成 `EmptyStubActivity`，点了直接回桌面**，
   所以代码检测到 Stub 就跳过，改开 `APPLICATION_DETAILS_SETTINGS` 应用详情页并提示
   「请在电池 / 自启动里允许小黑常驻」；控制页状态行会显示白名单是否已加入。
   真机也可以用 `adb shell dumpsys deviceidle whitelist +com.deepfish.xiaohei` 直接加。

权限：`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`、
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、`SCHEDULE_EXACT_ALARM`（API 31+ 拿不到精确
闹钟会降级成非精确 `set()`）、`POST_NOTIFICATIONS`（API 33+ 运行时申请，否则前台通知
不显示）。部分厂商 ROM 还要在「自启动管理」里手动允许，这个只能用户自己开。

**真机验证**：`BOOT_COMPLETED` 是系统保护广播，adb 发不了（会报 `Permission Denial`），
所以用 manifest 里走同一分支的 `BOOT_PROBE`（带 `FLAG_INCLUDE_STOPPED_PACKAGES`）
验证：进程全部杀掉 → 广播 → `boot probe` 日志 + `:pet` 进程起来 + 心跳闹钟
`startForegroundService` 已注册 + 前台通知在 + 宠物回到桌面；应用更新
（`MY_PACKAGE_REPLACED`）同样会自启。点「收起」后服务记录为 `(nothing)`、活动通知
消失、心跳取消、再发探针日志是 `boot skip: user turned pet off`；点「召唤小黑」全部恢复。

---

## 五、menu2 菜单是怎么跑起来的

先搞清楚原版的分工，才知道该补哪一半：

```
[渲染进程] 右键 / 长按
    └─ openMenu()  →  ipcRenderer.send('menu', menuState())
                            │
[主进程]  ipcMain.on('menu') ─┘
    ├─ model.build(st, opts)        ← require('renderer/menu2/model')
    └─ win.webContents.send('menu2', {t:'open', data})
                            │
[渲染进程]  Menu2.onMsg({t:'open', data})  → 画 DOM 气泡面板
    └─ 点中某项 → ipcRenderer.send('menu2-pick', id)
                            │
[主进程]  ipcMain.on('menu2-pick')
    ├─ id === 'full'  → 退回原生菜单
    └─ fn = dyn[id] || ACT[id]，是函数就执行
            └─ 多数 id → Actions.menuCmds() 映射成指令，send('cmd', 指令)
```

关键点：**面板本来就是画在渲染进程里的 DOM**（`menu2.js` 由 `app.js` 动态注入，
`Menu2.init({send, env})` 也由 `app.js` 调好），主进程只负责两件事——生成菜单数据、执行选中项。
所以 Android 侧只需要把这两件事接管过来，面板一个像素都不用重画。

`shim.js` 里对应的接管：

| 原版主进程职责 | shim 里的实现 |
| --- | --- |
| `ipcMain.on('menu')` → `openMenu2(st)` | `send` 拦截 `'menu'` → `__menuOpen(st)`：调 `MenuModel.build()`，再 `Menu2.onMsg({t:'open',data})` |
| `ipcMain.on('menu2-pick')` | `send` 拦截 `'menu2-pick'` → `__menuPick(id)`：查 `Actions.menuCmds()`，命中就 `__emit('cmd', 指令)`，否则交壳层处理 |
| `closeMenu()` → `send('menu2',{t:'close'})` | 拦截 `'menu-close'` → `__menuClose()` |
| `grabMenuInput()` → `send('menu-state',true)` | `__menuOpen` / `__menuPick` 里一并发 `menu-state` |

`model.js` 是 CommonJS 模块（`require` 兄弟模块 + `module.exports`），浏览器里跑不了。
用 `tools/wrap-cjs.js` 包一层最小模块作用域，导出挂到 `window.MenuModel`：

```bash
node tools/wrap-cjs.js app/src/main/assets/web/renderer/menu2/model.js
```

它 `require` 的 `keymap` / `actions` 在页面里已经是顶层 `const` 全局（不挂 `window`），
所以 `shim.js` 的 `require` 里按名字返回，而不是 `window.Xxx`。

**还有一个窗口层的坑**：舞台窗口是 `FLAG_NOT_TOUCHABLE` 的，手指根本碰不到页面里的面板。
所以菜单打开期间把命中区临时拉成全屏（`setMenuMode(true)`），关掉再收回宠物大小；
这期间所有触摸照旧合成成指针事件回灌页面，`document.elementFromPoint` 会命中面板上的 DOM。

合成事件也必须是完整序列——`menu2` 同时监听 pointer 和 mouse 两套事件：
`pointerdown → mousedown → pointermove → mousemove → pointerup → mouseup → click`，且都带 `composed:true`。

调试辅助：给页面发 `cmd "@js:<表达式>"` 会在页面里求值并把结果打回日志（`window.__dbg(x,y)` 可查某点的元素链）。

**要改菜单文字/图标，别动 `model.js`**：它被混淆过，改一处会牵连字符串表。
`model.build()` 出来的是一份普通对象，`shim.js` 在 `Menu2.onMsg` 之前做了两件事——
`markNoWin()` 给窗口类动作置灰、`relabelFind()` 把「找文件」换成「回主页」。
以后再有「某个菜单项手机上没有对应功能」，加一道同样的后处理就行。

**菜单格子点不准怎么查**：`shim.js` 里有个 `DBG_CELLS` 开关（默认 `false`），
打开后菜单每次打开会每隔 3 秒把面板里所有带尺寸的元素的
`标签|中心坐标|尺寸|图标名` 打到日志里（会递归进 shadowRoot——`menu2` 的面板挂在
`DIV.mroot` 下，普通 `querySelectorAll` 看不到）。
图标名就是格子 id（`hxback` / `hide` / `more`…），照着中心坐标点就行：

```
DIV.hit|474,622|120x68     ← 这一格的命中矩形中心
IMG|441,620|67x67|hxback   ← 图标 + id
```

真机截图是 1280×800，别拿视觉模型说的 1080×675 去算坐标，否则会偏一百多像素、点到隔壁格子。

### 菜单外观：贴纸宽度与键帽（手机适配）

`Menu2.onMsg` 之后还会跑一次 `fitStickers()`，管两件事：

1. **贴纸胶囊跟着文字走**。主圈七项里，只有「没有原版贴纸素材」的那几项（变成人 / 回主页 / 躲起来）是 CSS 画的贴纸，
   `model.js` 给它们写死的内联 `width` 是「按 Windows 设计稿量出来的旋转包围盒」。同样的字号落到 Android 的
   PingFang SC / 系统黑体上排出来更宽，三字名就会顶出彩色底、贴着白边。
   `fitStickers()` 把 `.mk.css .cs` 的内联宽度改成 `auto` 再补 8px 左右内边距，让胶囊刚好包住字。
   **另外四张（摸头 / 动作 / 嘿咻 / 更多）是原版贴纸 PNG，文字烘焙在图里，宽度改不了**——要改只能重画素材。
2. **键帽提示全部隐藏**。`i.chip`（双击）、`.cs .ks`（Ctrl Ctrl）、`.kcs`（二级格子右上角）都是 Windows 的
   鼠标/键盘手势说明，手机上既没有右键也没有中键，留着只会让人以为要那样点。

⚠️ **改 `menu2.css` 不一定立刻生效**：它是在 shadowRoot 里用 `<link rel=stylesheet>` 拉的
`http://pet.local/renderer/menu2/menu2.css`，由 `serveAsset()` 喂给 WebView。即使响应带
`Cache-Control: no-cache`，重装 APK 后仍常看到旧样式（`shim.js` 的改动却是生效的）。
所以「必须生效」的覆盖样式写成 `shim.js` 往 shadowRoot 末尾追加的 `<style id="xh-fix">`（带 `!important`），
`menu2.css` 里的同名规则只当备份/参考，两边要一起改。

### 大脸猫：整块屏幕当脸

菜单里原来那格「躲起来」（`hide`）改成了「大脸猫」：点一下整屏变成一张猫脸，
**只画两只眼睛，蓝鼻子/嘴/脸轮廓/耳朵全部不画**。

**整张脸是矢量画的，不加载图片**：`face.html` 只挂一个 canvas，`face.js` 用 Canvas 路径画眼睛。
眼睛大小直接按当前屏幕分辨率算：单眼宽 ≈ 屏宽 38%、高不超过屏高 74%（沿用原版眼白 92×112 的比例），
两眼整体居中；横竖屏切换、分辨率变化都会重新按整屏定尺寸，放大也不会糊。

**棕色外圈的白点锯齿已修掉**：旧画法是「白色眼白 `fill` 到 `rx`」+「棕色 `stroke`
外缘也正好到 `rx`」，两条抗锯齿边完全重合，白色的半透明边缘像素从棕色外缘渗出来，
在黑底上形成一圈断续白点（把截图放大 3 倍看得很清楚）。现在改成两层同心 `fill`：
棕色整块打底到 `rx`、白色只填到 `rx - lw`，棕色外缘只剩「棕→黑」的普通 AA；
环宽仍是 `lw`，视觉尺寸不变。同时 canvas 强制至少 **2x 超采样**
（`dpr = max(2, devicePixelRatio)`，真机 dpr 常是 1），内部多画一倍像素再由浏览器
缩小，所有弧线边缘都更平滑。真机日志 `face vp 1280x800 dpr=2` 确认生效，
前后对比见 `shots/eye-zoom-before.png` / `shots/eye-zoom-after.png`
（`shots/face-*.png` 里其余表情截图拍于超采样之前，仅作姿态参考）。

**表情是连续量，不是 9 帧位图**：`POSE` 里每个表情给的是 `openL/R`（眼睑开合 0~1）、
`arcL/R`（笑眼弧）、`pupil`（瞳孔大小）、`slant`（眼睑斜度）、`tilt`（两眼高低）、
`biasX/Y`（视线偏移）和 `heart` / `spiral`（心形/蚊香瞳孔权重 0~1）。`draw()` 每帧把当前姿态
向目标缓动，每个表情还能单独给 `ease`：眨眼 0.55、困/睡 0.06~0.10、日常 0.14~0.30。
整张脸另有呼吸缩放（睡着时几乎不动）。

**任意两表情之间是渐变，不闪切**——原先有两处硬切已改掉：
- 瞳孔形状原来是离散 `shape`（`round`/`heart`/`spiral`）一帧硬换。现在改成连续权重
  `cur.heart` / `cur.spiral`（跟着 `ease` 缓动），`drawEye()` 里按权重用 `globalAlpha`
  把圆瞳、红心、蚊香**交叉淡化**：从圆到心会经过「灰圆瞳 + 半透红心」的中间态，不会瞬间变色变形。
- 睁眼 ↔ 闭眼弧原来是 `if (arc>0.12) 弧 else 睁眼` 的硬阈值，一帧从整只眼跳成一条线。
  现在 `drawMorphEye()` 按 `mix=arc/3.2` 把两者**交叉淡化**，同时把 arc 类表情的 `open`
  压到 0.10（眼睛先闭上再出弧），过渡就是连贯的眯眼过程。
  眨眼序列 `blinkDown→blink` 也因此平滑。
- 复查其余姿势又揪出两处漏网的硬阈值，一并改成连续量：
  - **眼睑线**原来是 `if (open<0.60 || |slant|>0.02)` 到阈值才突然冒出一条线。
    现在线强度 `lineA=max((0.62-open)/0.30, (|slant|-0.015)/0.12)` 随压眼皮/皱眉程度渐显，
    用 `globalAlpha` 叠出半透明过渡，不再闪线；全睁且无斜度时仍完全不画（爱心/好奇/晕不顶浮空线）。
  - **呼吸幅度**原来是 `mood==='closed' ? 0.002 : 0.006` 入睡瞬间跳变。
    现在用连续缓动的 `sleepK`（0~1）在 0.006↔0.002 间插值，入睡是慢慢收住呼吸。
真机已截三组中间态验证渐变而非跳变：形状 `midheart`（灰圆+半红心）、`midspiral`（灰圆+半螺旋）、
`midarc`（半透明眼+弧）；眼睑线 `lidOff/lidEdge/lidMid/lidFull`（无→淡→中→浓）与 `slantMid`（半透斜眉）。

表情表在原有 `surprised / half / meh / petted / happy / closed / blink` 之外新增：
`curious`（歪头斜看）、`angry`（皱眉怒视）、`sad`（垂眼委屈）、`sleepy` / `yawn`（困／哈欠）、
`dizzy`（蚊香眼）、`love`（爱心眼）、`wink`（单眼眨）、`blinkDown`（眨眼第一阶段）、
`smile`（温柔微笑，比 `happy` 的 `arc:7.5` 更浅的 `arc:5.0` 闭眼弯月弧）。
`seq` 短序列把「好奇→眨眼」「困→哈欠→惊醒」「生气→不屑」「晕→恢复」「委屈→平复」「微笑→睁开」
串成小段。

**点一下是「切换到一个表情并停住」，不是连 flash**：原先点一下要连播
「惊→红心→单眼眨→眯眼笑→微笑」五段，其中 wink/happy/smile 三个都是闭眼弧、
连着闪过就是「眯眯眼又换成别的」；而且 wink 只给 240ms，缓动还没到位就被切走
（实测切走时 heart 还剩 0.27、arcL 才 4.5/6.2）。现在点一下只走
「惊一下 130ms → 目标表情 900ms」，然后 `base` 停在该表情，直到下次点击或 idle 轮换。

**点击池是全部 14 个表情，不是固定三四个**：`TOUCH_MOODS` 收了 POSE 里所有能独立
展示的表情（`surprised / half / meh / curious / angry / sad / sleepy / yawn / dizzy /
love / smile / petted / wink / happy`），只排除 `blinkDown` / `blink` 两个眨眼过渡帧
和 `closed` 睡眠态。用「洗牌袋」发牌：一轮打乱后每个表情各出现一次、发完再洗，
所以短期内不会连着抽到同一个；再按视觉分组（睁眼普通 / 半睁眯眼 / 特殊瞳孔 / 闭眼弧）
贪心重排发出顺序，让相邻两张（含跨轮那张）都不同组，避免 `smile→happy` 这种两个弧眼
连着出、看着像没换表情。真机连点 16 次验证：首轮 14 个全部覆盖、相邻同组 0 次、
页面只加载一次，稳定态渲染与默认大脸一致、无回归。
空档每 5.2–11 秒换一次表情，90 秒没人碰就闭眼睡，碰一下会被摸醒。

**爱心眼是红色 + 心跳节奏**：`shape='heart'` 时瞳孔填充 `#e11d2a`（红，黑底上够醒目），
并由 `heartbeat(ts)` 按 900ms 周期做「咚—哒——」双峰缩放（峰值 ×1.16，两个高斯峰分别落在
周期 0.10 / 0.27 处），以 30fps 渲染就能看到心形一张一缩真机实测宽度在 190~210px 间起伏。
高光点保留，红心上有一点白反光更像玻璃珠。

**瞳孔跟着手指看，没人碰也会自己游移**：`drawEye()` 里先 clip 到眼白椭圆，再把黑瞳按
`gazeX/gazeY` 偏移，眼珠左右看时不会糊到棕色描边上；每帧向目标插 0.07，松手后自然停住。
2.6 秒没碰就切成两个不同周期的正弦漂移，避免两颗眼珠一直钉在屏幕中心。幅度仍是 0.12/0.10，
调到 0.16 会露出很宽的月牙白边，像翻白眼。`biasX/Y` 让好奇、生气、委屈这类表情自带视线方向。

**尺寸自愈**：WebView 首次布局前 `innerWidth` 可能是 0，某些 ROM 不补发 `resize` 事件，
日志里真出现过一次 `face vp 0x0`，所以 `draw()` 每帧对一次视口尺寸，漏事件也能自己恢复。

**为什么不开新 Activity**：这台 ROM 在离开前台 Activity 时会 force-stop 整个包（控制页召完猫
必须立刻 `finish()` 就是这个原因）。所以大脸还是 `PetService` 里的一个全屏悬浮窗：
`MATCH_PARENT` + `FLAG_LAYOUT_IN_SCREEN` + `FLAG_LAYOUT_NO_LIMITS`，`PixelFormat.OPAQUE`。
打开时把舞台 WebView 设成 `INVISIBLE`（桌面猫让位，页面还在跑），`closePage()` 里恢复。

退出只有右下角那颗淡的 `×`——全屏窗口收不到返回键，必须留一个能点的。

### 动作点了没反应？是这三层里的一层

从点菜单到真正播动画，中间有三道关，每一道都能让动作"看起来失效"：

1. **id 有没有对应指令**：查 `Actions.menuCmds()[id]`。有就发 `cmd`，没有就交给壳层（设置 / 关于 / 退出这些）。这一层没出过问题。
2. **指令能不能进队列**：页面把动作丢进 solo 队列（`Pet.queued`），宠物在走路、在播别的动作、图集还在加载时都会排队。**Android 上单张大图解码要 2–8 秒**，排队项等不到 solo 空出来就被判超时丢弃。
3. **动作有没有落点**：`jump`（上窗口）、`daze`（趴着无聊）、`perchtry`、`hxguard`（守窗口）都要求 `World.wins` 里有窗口——那是原版主进程枚举桌面窗口塞进来的。Android 拿不到跨应用窗口信息，这个数组永远是 `[]`。

三处对应的处理：

| 层 | 现象 | 处理 |
| --- | --- | --- |
| 2 | 排队被判超时后静默丢弃 | `shim.js` 的 `armRetry()`：发出后每 2.5 秒查一次 `Pet.queued`，指令还挂着就再推一次，最多 3 次（覆盖 7.5 秒，够等一次图集加载） |
| 3 | 窗口类动作点了静默结束 | `markNoWin()` 在 `Menu2.onMsg` 之前把这几项标 `dis` 并加副标题「手机上没有窗口」，置灰不可点 |
| — | `v2tv/anim` 等 134 张帧当初是密文，动作执行了但画不出来 | 见第三节，解密后恢复正常 |

重试的实测日志（先用 `tea` 占住 solo 通道，再点「歪头」）：

```
pick tilt -> tilt
["solo wait tilt"]        ← tea 的图集还在加载，第一次被丢
retry tilt #1 {"mode":"rest","queued":{"c":"tilt","age":1.62}}
act tilt                  ← 重发成功
```

`World.wins` 为空这件事本身还有解：给页面注入一个合成窗口（比如屏幕上方一条带），`jump` / `daze` 就有地方趴了。但原版的趴窗逻辑依赖真实窗口的坐标和遮挡关系，随便塞一个矩形容易让宠物停在半空，所以暂时选择置灰而不是硬凑。

---

## 六、小窗口（关于 / 怎么玩 / 进群）

原版这三个菜单项**不是**发指令，而是主进程另开一个 Electron 窗口：

```js
showAbout()   → popupWindow({ width: 560, file: 'about.html' })     // 关于小黑
openWish()    → popupWindow({ file: 'wish.html', preload: 'wishpreload.js' })
openWelcome() → popupWindow({ file: 'welcome.html', query: {first, zoom} })  // howto 和 group 都走它
showFinder()  → 找文件窗口（finder/host.js）
```

这些页面是「哑」的：只认 `require('electron').ipcRenderer`，数据全靠主进程喂：
`about-state` / `groups-state` / `welcome-login-state` 取数据，`about-do` / `welcome-do` 关窗口，
`about-size` / `welcome-size` 把内容高度报回去让窗口自己长高。

所以接法就是**再造一个悬浮窗，把「主进程那半边」搬进页面**：

| 原版主进程 | `pageshim.js` |
| --- | --- |
| `ipcMain.handle('about-state')` 组装版本/更新/群/怎么玩 | 版本与 OTA 状态由 `AndroidPage.aboutState()` 从原生状态机取（`hasUpdater:true`），群/怎么玩本地拼 |
| `ipcMain.handle('groups-state')` 读 groups 缓存 + `visible()` | `fetch('../groups.default.json')`，`visible = groups.filter(g => !g.full)` |
| `ipcMain.on('about-do'/'welcome-do')` 关窗口 | `send` → `AndroidPage.post({k:'close'})` |
| `about-size` → `win.setBounds` | `post({k:'size', h})` → Kotlin 按内容高度重设窗口 |
| `copy-text` | `post({k:'copy', v})` → 系统剪贴板 |

`require('electron')` 在页面里被 `pageshim.js` 接管成一个假 `ipcRenderer`：`invoke` 返回本地/原生数据，
`send` 处理关窗口、改高度、复制，以及 `check / install / auto / unskip` 这些更新动作
（`post({k:'ota', v:'...'})` → `PetService.onOtaAction`）。原生状态变化时推 `about` 事件，
`__pageEmit` 与本地那份合并后再渲染，所以 `about.html` 的状态判断逻辑没改。

**为什么不直接用 `welcome.html`**：它的主打是 8 段鼠标演示视频，而 `assets/video/*.webm`
在原版安装包里根本不存在（见下一节）。与其放一个空播放器，不如按同一套卡片样式写 `howto.html`。

**三张卡片各管一块，内容不重叠**（原版 `about.html` 把版本、群卡、怎么玩全塞在一页，
三个菜单项打开的其实是同一份内容）：

| 菜单项 | 文件 | 内容 |
| --- | --- | --- |
| 关于 | `about.html` | 版本号、更新状态、作者、版权声明 |
| 怎么玩 | `howto.html` | 7 条手势说明、作者、版权声明 |
| 进群 | `group.html` | 群二维码（企业微信 / 公众号）、QQ 群号 + 复制、作者 |

`about.html` 只删了「小黑的家」「怎么玩」两个 `<section>` 和对应的 `renderGroups` / `howto` 渲染，
其余逻辑原样；`group.html` 是新写的，样式从 `about.html` 的群卡部分照搬，`groupcard.js` 仍用原版。

尺寸上有个要注意的：这些页面按窗口宽度排版（520 / 560），高度是内容自然高度。
1280×800 的机器上留给窗口的只有 800−16×2=768，三页都远低于这个上限，不用再压。
实在放不下时 WebView 可滚动，右上角 × 始终可见。
窗口开着的期间命中区被移到 −10000，卡片范围外的触碰不会再摸到宠物。

**「找文件」改成了「回主页」**：原版这个位置是 Windows 资源管理器的搜索窗口，
手机上没有任何对应物。现在同一位置、同一手感，点了就按一次 Home 键
（`resolveActivity(ACTION_MAIN + CATEGORY_HOME)` 取默认桌面再显式启动，避免多桌面弹选择框）。
菜单文字和图标也换了：`shim.js` 的 `relabelFind()` 在 `model.build()` 之后把 `id==='find'`
的格子改成「回主页」，图标用原版图标集里的 `hxback`（原版「回到任务栏」用的那个回退箭头，
人形取 `hm_hxback` / `hw_hxback`）。名字限定三个字——主环气泡的宽度只够三字，
四字的「回到主页」会被截成「回到主」。

还没做的两项：`wish`（要连作者服务器 + 传图）和 `update`（没有 autoUpdater），菜单里点了给 toast。

---

## 七、原版就缺的东西（不是移植问题）

`assets/video/*.webm`（breath / sidestretch / ironball / eat 四个动作的动画）**在安装包里就不存在**——全盘 `find -iname '*.webm'` 计数为 0，`video/` 下只有 28 个描述定位的 `.json`。
渲染层对这种情况有降级（`mvClips[..].bad` 标记后走精灵帧），所以 Windows 上一样报 `video error`。
日志里的 `videoact video error xxx` 属原版固有行为，不影响功能。

除这一项之外素材是全的。逐目录核对过（原版 `resources/app.asar.unpacked/assets` ↔ 仓库 `assets/`，文件数逐个对齐）：

| 目录 | 文件数 | 构成 |
| --- | ---: | --- |
| `v2/` | 34 | 32 张分层立绘 PNG（XHA1 加密）+ `rig.json` + `scene.json` |
| `v2tv/` | 167 | 162 张整图/动画帧 PNG（加密，其中 134 张在 `anim/` `stow/` `jump/` `meal/` 子目录）+ 5 个 json |
| `settings/` | 25 | 19 张设置页图标 PNG（加密）+ 6 个其它 |
| `video/` | 28 | **全是 `.json` 定位文件，没有 webm** |
| `sound/` | 15 | 未加密，直接拷 |
| `tutorial/` | 2 | `road.jpg` + `来源.txt` |

加密 PNG 合计 213 张（32+162+19），`tools/decrypt-assets.js` 全量跑过一遍，213/213 与原版封包解出来逐字节一致。所以「缺」的只有 webm 这一类，不是移植漏拷。

---

## 八、还没做的

1. **APK 体积**：103MB，其中 assets 104MB（PNG 用 `noCompress` 直存，压不动）。可裁的有 `menu2/icons` 里没被菜单用到的 113 个图标（约 8.5MB）、`v2tv` 里未引用的帧。
2. **许愿墙 `wish`**：要连作者服务器上传图片，手机端暂时只给 toast。
   （「检查更新」已在 v1.0 做成真 OTA，见第十章；Android 不能静默安装，最后一步仍需用户点确认。）
3. **「躲起来」已改成「大脸猫」**：`hide` → `openFace()`，菜单里不再有隐藏语义，只有「退出小黑」(`quit`) 走停机。
   大脸本身已经做完（唯一的重画版，矢量画两眼，无鼻/嘴/轮廓/耳朵）；**没还原的是原版 `hidePet()`**：
   播 `hideaway` → 3 秒后窗口移出屏幕、托盘 `recall` 召回。要做得加通知栏「召回」入口。
4. **气泡/说话**：`say` 走的是页面内绘制，已验证能画；但气泡点击（`bubble-click`）没单独测。
5. **体型调节 UI**：`bodyScale` 只在 `PetPrefs` 里（默认 1.3，原版 `BodyFit.LIMITS` 上限就是 1.3），控制页没有滑杆。
6. **TV 形态的变形动画**：依赖缺失的 webm，只能看精灵帧降级效果。
7. **小窗口不能拖动**：原版窗口可以拖标题栏，这里固定在屏幕居中。
8. **窗口类动作**：`jump` / `daze` / `perchtry` / `hxguard` 依赖 `World.wins`，Android 拿不到跨应用窗口，目前置灰（副标题「暂无窗口」）。要做就得给页面注入合成窗口，还得处理趴窗坐标，容易停在半空，暂不动。
9. **四张图片贴纸的字贴边**：摸头 / 动作 / 嘿咻 / 更多的文字是画在 PNG 里的，Android 上字体略宽时会显得挤。要么重画这四张贴纸，要么也改成 CSS 画（图标素材 `menu2/icons` 里都有）。

---

## 九、版权

原版渲染层、立绘、音效均来自《小黑桌宠》安装包，仅供本机个人学习研究使用，不要商用。
「罗小黑」角色版权归北京寒木春华动画技术有限公司所有。

> **注意**：v1.0 起本仓库在 GitHub 公开发行，Release 附件里的 APK 内含上述原版素材。
> 若收到权利人通知会立即下架。若你不希望公开这些素材，可选做法：把
> `app/src/main/assets/web/assets/` 加进 `.gitignore`（仓库只留适配层源码），
> 并把 OTA 改成私有源（自建 `update.json` + APK，用 `files/ota.txt` 换源）；
> 代价是别人 clone 后需要自行按第三章的步骤从原版安装包补素材才能编译。

---

## 十、版本升级（OTA）与 GitHub 发版

**v1.0**（`versionCode=100`，tag `v1.0`）。方案照搬另一个项目
[Switch-HA](https://github.com/coolsmile1026/Switch-HA) 的 `source/update.c`，把 NRO 换成 APK。

### 用户侧：三个入口，同一个状态机

| 入口 | 行为 |
| --- | --- |
| 菜单「检查更新」/「更多 → 关于」 | 打开关于页并联网查一次（30 分钟内复用上次结果） |
| 控制页「版本 1.0 · 检查更新」 | 服务没跑就先召唤，再打开关于页检查 |
| 关于页 | 「检查更新 / 现在更新 / 重试 / 看看更新了什么 / 自动检查更新」全在这里 |

流程：拉 manifest → 版本比较 → 有新版本显示版本号和「现在更新」→
点击后（未下载则先下载）走**多线路竞速**下载，实时百分比进度条 →
校验 → 交给系统安装器，用户点确认后替换自己。
「自动检查更新」打开时，服务启动后台查一次（6 小时内不重复）并只下载不弹安装，
状态写成 `ready`，下次打开关于页直接点「现在更新」即可。

**状态文件** `files/pet_ota.state.json` 是唯一真相：服务在 `:pet` 进程、控制页在主进程、
关于页在另一个 WebView，全靠它同步。`PetService.setOtaUpdate()` 写完就往关于页推
`about` 事件，`pageshim.js` 合并本地数据后让原版 `about.html` 自己渲染。

### 更新源与镜像代理（国内网络重点）

主源是 GitHub Releases API，备用源是仓库根目录的 `update.json`（raw）：

```
https://api.github.com/repos/coolsmile1026/xiaohei-pet-android/releases/latest
https://raw.githubusercontent.com/coolsmile1026/xiaohei-pet-android/main/update.json
```

`release` 附件由 `github.com` 302 到 `objects.githubusercontent.com`，这条链路在国内
经常极慢或被重置。所以**检查和下载都带镜像前缀**，并且都先并发竞速：

1. **manifest**：候选 = 主源直连 + 各镜像前缀 + 主源 + raw 直连 + 各镜像前缀 + raw，
   全部同时发，谁先返回一份能解析的 manifest 就用谁（9 秒总上限）。
2. **下载**：候选 = `browser_download_url` 直链 → API asset 直连
   （`Accept: application/octet-stream`，绕过 302）→ 各镜像前缀 + 直链。
   每条线路先探 **64KB** 计时，2.5 秒内按快慢排序，谁快谁先下整包；
   下载中还有低速熔断（12 秒不足 64KB 直接换下一条），不在死线路上干等。
3. 镜像前缀沿用 Switch-HA 实测过的那组：`gh-proxy.com`、`ghfast.top`、
   `gh-proxy.net`、`ghfile.geekertao.top`、`ghproxy.net`。
   本机实测 API 直连 200 / `gh-proxy.com` 200，其余对 API 路径有 403/超时，
   竞速会自动把它们排到后面或跳过——不需要手工挑线路。

支持三种 manifest：GitHub Releases API（取 `tag_name` + assets 里第一个 `.apk`）、
自托管 JSON `{"version":"1.1","url":"...","notes":"...","size":...}`、纯文本（第一行版本、第二行地址）。
版本号逐段数字比较，忽略 `v` 前缀，`1.0` 与 `1.0.0` 相等，`1.10 > 1.9`。

**换源**：在设备 `files/ota.txt` 写（本机调试/自建私有源用）：

```
# 第一行：manifest URL
https://example.com/my/update.json
# 追加镜像前缀（写了就替换内置列表）
mirror: https://ghfast.top/
# 完全关掉内置镜像
# mirror: none
```

### 下载校验与安装

下载到 `cacheDir/ota/download.part`，完成后校验 ZIP 魔数、大小、
`PackageManager.getPackageArchiveInfo` 能解析、包名一致、版本不低于 manifest、
**签名与当前安装的一致**（不一致系统会拒装，提前拦掉别让用户白等）。
校验通过才改名为 `xiaohei-pet.apk`，通过自家 `ApkProvider` 以
`content://com.deepfish.xiaohei.apk/ota/xiaohei-pet.apk` 交给系统安装器
（`ACTION_VIEW` / `ACTION_INSTALL_PACKAGE`，`FLAG_GRANT_READ_URI_PERMISSION`）。
Android 7+ 不能传 `file://`，项目又不依赖 androidx，所以自己写了这个 40 行的 provider。

> **签名一致性**：目前发布的是 **debug 签名** APK（`~/.android/debug.keystore`）。
> 换机器或用别的 key 重签，用户必须卸载重装才能升级。正式分发前应先固定一把 release key。

### 发新版

一条命令（编译 + 校验元数据 + 推 main/tag + 建 Release + 传两个附件 + 校验 latest）：

```bash
./tools/release.sh --publish
# 只出包不发布：./tools/release.sh
# 只重发 Release 不重新编译：./tools/release.sh --publish --no-build
```

令牌优先读 `GITHUB_TOKEN`，否则从 macOS 钥匙串取（和 Switch-HA 同一把）。
手动流程与**发版铁律**：

1. 改 `app/build.gradle.kts`：`versionName`（如 `1.1`）和 **`versionCode` 必须递增**
   （1.0→100、1.1→110、1.0.1→101），否则系统按「降级」拒装。
2. 编辑 `update.json` 的 `notes`，运行脚本会自动回填 `version / url / size`。
3. tag 必须是 `v<versionName>`，附件名必须是 `xiaohei-pet-v<版本>.apk`，
   四者（versionName / tag / 附件名 / update.json.version）对不上，
   客户端会每次都提示有新版、装完还是旧版。
4. **不要勾 Pre-release、不要停在 Draft**：`/releases/latest` 只返回已发布的正式版。
5. Release 的 `body` 取 `update.json` 的 `notes`，「看看更新了什么」显示的就是它。
6. 仓库必须公开：镜像代理和 OTA 客户端都是匿名读取，私有仓库的附件下不到。

### 真机验证

设备 S100V8H / Android 9 / 1280×800，2026-11-24 实测（`XiaoheiOta` 日志 + `files/pet_ota.state.json`）：

| 场景 | 操作 | 结果 |
| --- | --- | --- |
| 关于页与版本 | 控制页「版本 1.0 · 检查更新」→ 关于页 | 标题「小黑桌宠 / 版本 1.0」，更新行「正在检查新版本…」 |
| 仓库还没发布 | 同上（此时 GitHub 上还没有这个仓库） | 12 条 manifest 候选全部 404 / 超时 →「所有线路都没拿到版本信息」+「重试」 |
| 发布后走默认源 | 关于页「重试」 | `manifest candidates 12`，`winner [1] gh-proxy.com/... -> v1.0`（直连 API 这一秒稍慢，镜像抢先）→「✓ 已经是最新版」 |
| 只留镜像前缀 | `files/ota.txt` 第一行 `https://gh-proxy.com/https://api.github.com/.../releases/latest` + `mirror: none` | `winner [0] gh-proxy.com/... -> v1.0`，同样「已经是最新版」 |
| 发现新版 → 下载 → 校验 | 局域网假源 `{"version":"1.1","url":".../fake.apk","size":2097152}` | 状态 `available / 1.1`；点「现在更新」→ `dl [0] ok 2097152B in 0s` → `下载包校验不通过`（假包不是 APK）→ `error 下载失败，所有线路都不通` |
| 附件线路 64KB 探测 | `curl -r 0-65535`（与 `OtaUpdater.probe()` 同逻辑） | 直连 `206 65536B 2.18s`；`gh-proxy.com` `206 3.09s`；`ghproxy.net` `206 11.4s`；`ghfast.top` 超时 |
| raw 备用源 | `curl raw.githubusercontent.com/.../update.json` | 本机直连被重置（`HTTP 000`）；`gh-proxy.com` + raw `200 830B`，内容里 `size=108333167` |
| 安装入口 | `pm query-activities -a android.intent.action.VIEW -t application/vnd.android.package-archive` | 系统有安装器 Activity，`ACTION_VIEW` 能解析；`ApkProvider` 以 `content://` 授权 |

> **没做的一步**：没有让 1.0 真的覆盖安装 1.0（同版本系统只会当重装，108MB 走无线 adb 也慢）。
> 安装环节已验证到「Intent 可解析 + 包名/版本/签名三重校验」，等下一个版本（`versionCode=110`）
> 按同样流程点「现在更新」就能完成端到端验证。

截图：`shots/ota-about.png`（关于页「已经是最新版」）、`shots/ota-control.png`（控制页版本行与状态行）。

### 发布记录

| 项 | 值 |
| --- | --- |
| 仓库 | <https://github.com/coolsmile1026/xiaohei-pet-android>（public，492 个文件，约 105MB，几乎全是 `assets/` 素材） |
| Release | <https://github.com/coolsmile1026/xiaohei-pet-android/releases/tag/v1.0>（非 draft / 非 prerelease） |
| 附件 | `xiaohei-pet-v1.0.apk`（108,333,167 B）+ `update.json`（830 B） |
| `releases/latest` | `tag_name=v1.0`，assets 两个都在 |
| 一键重发 | `./tools/release.sh --publish --no-build` |

> 仓库和 Release 都是公开的，APK 里含原版立绘与音效，版权风险见第九章。要收回这些素材，
> 把 `app/src/main/assets/web/assets/` 加进 `.gitignore` 并改用自建私有 OTA 源即可（客户端已支持 `files/ota.txt` 换源）。
