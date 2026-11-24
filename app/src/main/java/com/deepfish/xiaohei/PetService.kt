package com.deepfish.xiaohei

import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * 悬浮舞台：一个透明置顶窗口，里面装 WebView，跑原版的渲染层。
 *
 * 原版 Electron 是「整屏透明窗口 + 鼠标穿透 + hit 事件开关」，安卓没有等价物：
 * 这里先给一个可触摸的舞台窗口（尺寸可按屏幕比例调），页面收到的都是真实触摸事件，
 * 所以拖动、单击、长按、菜单全部走它自己的逻辑，一行都不用改。
 * 全屏 + 命中区穿透留到下一期。
 */
class PetService : Service() {

    companion object {
        private const val TAG = "XiaoheiPet"
        private const val WEB_TAG = "XHWEB"
        private const val CHANNEL_ID = "xiaohei_pet"
        private const val LOCK_NAME = "pet_stage.on"

        const val ACTION_SHOW = "com.deepfish.xiaohei.SHOW"
        const val ACTION_HIDE = "com.deepfish.xiaohei.HIDE"
        const val ACTION_CMD = "com.deepfish.xiaohei.CMD"
        const val ACTION_SETTINGS = "com.deepfish.xiaohei.SETTINGS"
        const val ACTION_SOUND = "com.deepfish.xiaohei.SOUND"
        const val ACTION_FRONT = "com.deepfish.xiaohei.FRONT"
        const val ACTION_DOCK = "com.deepfish.xiaohei.DOCK"
        const val ACTION_OTA = "com.deepfish.xiaohei.OTA"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_FRONT = "front"
        const val EXTRA_OTA = "ota"
        private const val STAGE_INSET = 44
        private const val KEEPALIVE_REQ = 1001
        private const val KEEPALIVE_MS = 5 * 60 * 1000L
        const val ACTION_PROBE = "com.deepfish.xiaohei.BOOT_PROBE"

        private const val ORIGIN = "http://pet.local"

        fun lockFile(c: Context): File = File(c.filesDir, LOCK_NAME)

        fun canOverlay(c: Context): Boolean = Settings.canDrawOverlays(c)

        // 开机自启标志用文件而不是 SharedPreferences：服务在独立进程 :pet，
        // SP 的进程内缓存跨进程不可靠，和锁文件一样走 filesDir 才读得到最新值。
        // 默认开（文件不存在）；用户点「收起」写文件关掉，点「召唤」再删掉。
        private fun autoBootOffFile(c: Context): File = File(c.filesDir, "pet_stage.autoboot.off")
        fun autoBoot(c: Context): Boolean = !autoBootOffFile(c).exists()
        fun setAutoBoot(c: Context, on: Boolean) {
            try {
                if (on) autoBootOffFile(c).delete() else autoBootOffFile(c).writeText("0")
            } catch (_: Exception) {
            }
        }

        // 音效总开关同样走 filesDir：控制页在主进程、服务在 :pet，SP 跨进程读不到最新值。
        // 默认开（文件不存在）。原版由 Electron 主进程下发完整 settings，
        // 移植版原先只推了 bodyScale，Sound.S.soundOn 一直是初始的 false，
        // 结果每次 play() 都在门控处返回，日志是 "[sound] xxx muted (off)"。
        private fun soundOffFile(c: Context): File = File(c.filesDir, "pet_sound.off")
        fun soundOn(c: Context): Boolean = !soundOffFile(c).exists()
        fun setSoundOn(c: Context, on: Boolean) {
            try {
                if (on) soundOffFile(c).delete() else soundOffFile(c).writeText("0")
            } catch (_: Exception) {
            }
        }

        // 小猫音量也走 filesDir（跨进程可靠）。范围 0..100，默认 100；
        // 控制页按 10 一档调，evalSettings 把值交给 Sound.setSettings。
        // 原版 setSettings 会重算 cur 里的音量和 loops 的增益，所以调节即时生效。
        private fun volumeFile(c: Context): File = File(c.filesDir, "pet_volume.level")
        fun volumeLevel(c: Context): Int = try {
            val v = volumeFile(c).readText().trim().toIntOrNull()
            if (v == null || v !in 0..100) 100 else v
        } catch (_: Exception) {
            100
        }
        fun setVolumeLevel(c: Context, v: Int) {
            try {
                volumeFile(c).writeText(v.coerceIn(0, 100).toString())
            } catch (_: Exception) {
            }
        }

        /** 控制页改了音效开关 / 音量后，让活着的服务立刻把设置重新推给舞台页面 */
        fun pushSettings(c: Context) {
            if (!isRunning(c)) return
            try {
                c.startService(Intent(c, PetService::class.java).setAction(ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }

        // ---------------------------------------------------------------- dock 上沿
        // 只针对 Aerial 屏保桌面（app.orchard.tv）：dock 上沿由 DockWatchService
        // 读 launcher 节点树里那一排 5 个真实图标算出来，结果落在 DockScan 的 JSON。
        // 这里不再有「按屏高比例估算」的预设，也不考虑美好家智能桌面。
        private fun dockOffFile(c: Context): File = File(c.filesDir, "pet_dock.off")
        fun dockEnabled(c: Context): Boolean = !dockOffFile(c).exists()
        fun setDockEnabled(c: Context, on: Boolean) {
            try {
                if (on) dockOffFile(c).delete() else dockOffFile(c).writeText("0")
            } catch (_: Exception) {
            }
        }

        /** 控制页改了开关 / 落脚微调后立刻下发；识别结果变化由无障碍服务主动下发 */
        fun pushDock(c: Context) {
            if (!isRunning(c)) return
            try {
                c.startService(Intent(c, PetService::class.java).setAction(ACTION_DOCK))
            } catch (_: Exception) {
            }
        }

        /**
         * 控制页在前台时，悬浮命中区会盖住右半边按钮（真机点 +/− 被宠物吃掉，
         * 日志是 "touch down" 而不是按钮点击）。这时把命中区临时移出屏幕，
         * 返回桌面再摆回去；只动窗口位置，不影响渲染和保存的状态。
         */
        fun setControlFront(c: Context, front: Boolean) {
            if (!isRunning(c)) return
            try {
                c.startService(
                    Intent(c, PetService::class.java).setAction(ACTION_FRONT).putExtra(EXTRA_FRONT, front)
                )
            } catch (_: Exception) {
            }
        }

        /** 控制页的「切换形态」原版没有音效 CUE，这里补一声猫叫当反馈 */
        fun playSound(c: Context, id: String) {
            if (!isRunning(c)) return
            try {
                c.startService(
                    Intent(c, PetService::class.java).setAction(ACTION_SOUND).putExtra(EXTRA_SOUND, id)
                )
            } catch (_: Exception) {
            }
        }

        fun isBatteryExempt(c: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .isIgnoringBatteryOptimizations(c.packageName)
                } catch (_: Exception) {
                    false
                }
            } else true

        // 常驻心跳：getForegroundService 是 API 26+ 给「后台拉起前台服务」的豁免通道，
        // 比从普通广播里 startService 稳。每 5 分钟打一次：服务活着只是续期
        // （onStartCommand 幂等），被 ROM 杀掉则到点自动拉回来。
        private fun keepAlivePi(c: Context): PendingIntent {
            val i = Intent(c, PetService::class.java).setAction(ACTION_SHOW)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                PendingIntent.getForegroundService(c, KEEPALIVE_REQ, i, flags)
            else
                PendingIntent.getService(c, KEEPALIVE_REQ, i, flags)
        }

        fun scheduleKeepAlive(c: Context) {
            if (!autoBoot(c)) {
                cancelKeepAlive(c)
                return
            }
            val at = System.currentTimeMillis() + KEEPALIVE_MS
            try {
                val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, keepAlivePi(c))
                else
                    am.setExact(AlarmManager.RTC_WAKEUP, at, keepAlivePi(c))
            } catch (_: Exception) {
                // 部分 ROM / Android 12+ 不给精确闹钟权限，退化成非精确闹钟照样能保活
                try {
                    (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                        .set(AlarmManager.RTC_WAKEUP, at, keepAlivePi(c))
                } catch (_: Exception) {
                }
            }
        }

        fun cancelKeepAlive(c: Context) {
            try {
                (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(keepAlivePi(c))
            } catch (_: Exception) {
            }
        }

        fun show(c: Context) {
            setAutoBoot(c, true)
            val i = Intent(c, PetService::class.java).setAction(ACTION_SHOW)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "start fgs failed: $e")
                try {
                    c.startService(i)
                } catch (_: Exception) {
                }
            }
        }

        /** 收起：先让服务自己存盘再退，别直接砍进程把存档丢掉 */
        fun hide(c: Context) {
            // 用户主动收起后，开机自启和心跳都别再把他拉起来
            setAutoBoot(c, false)
            cancelKeepAlive(c)
            if (isRunning(c)) {
                try {
                    c.startService(
                        Intent(c, PetService::class.java).setAction(ACTION_HIDE)
                    )
                } catch (_: Exception) {
                }
            }
            try {
                lockFile(c).delete()
            } catch (_: Exception) {
            }
        }

        fun isRunning(c: Context): Boolean = lockFile(c).exists()

        /** 给页面发一条原版命令，例如 "form human" / "form cat" / "compact" */
        fun command(c: Context, cmd: String) {
            try {
                c.startService(
                    Intent(c, PetService::class.java).setAction(ACTION_CMD).putExtra(EXTRA_CMD, cmd)
                )
            } catch (_: Exception) {
            }
        }

        /** 控制页 / 关于页触发 OTA 动作（服务在 :pet 进程，必须走 startService 转发） */
        fun otaCommand(c: Context, action: String) {
            try {
                c.startService(
                    Intent(c, PetService::class.java).setAction(ACTION_OTA).putExtra(EXTRA_OTA, action)
                )
            } catch (_: Exception) {
            }
        }

        /** 控制页状态行用：读 OTA 状态文件，不依赖服务是否活着 */
        fun otaStatusLine(c: Context): String {
            val s = OtaUpdater.readState(c)
            val u = s.optJSONObject("update") ?: org.json.JSONObject()
            return when (u.optString("kind")) {
                "checking" -> "正在检查更新…"
                "downloading" -> "正在下载 v${u.optString("version")} ${u.optInt("percent")}%"
                "installing" -> "正在安装…"
                "error" -> "检查失败：${u.optString("message", "连不上更新服务器")}"
                "skipped" -> "已跳过 v${u.optString("version")}"
                "available", "ready" -> "发现新版 v${u.optString("version")}（打开关于页更新）"
                "latest" -> "已是最新版"
                else -> "未检查"
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var web: WebView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var hitView: View? = null
    private var hitLp: WindowManager.LayoutParams? = null
    private var stageW = 0
    private var stageH = 0
    private var stageOriginX = 0
    private var stageOriginY = 0
    private var destroyed = false

    // 长按识别：原版菜单由主进程负责，安卓这边由命中区自己判长按
    private var menu: PetMenu? = null
    private var downAt = 0L
    private var downX = 0f
    private var lastTapMeow = 0L
    private var downY = 0f
    private var downStageX = 0
    private var downStageY = 0
    private var longFired = false
    private val longPress = Runnable { onLongPress() }
    private var touchDown = false
    private var boxL = 0
    private var boxT = 0
    private var boxR = 0
    private var boxB = 0
    private var boxW = 0
    private var menuMode = false
    private var menuGuard: Runnable? = null
    private var controlFront = false
    private var dockOffset = 0
    private var dockTarget = 0
    private var dockAnim: ValueAnimator? = null
    private var dockPoll: Runnable? = null
    private var formCat = false
    /** 页面上报的宠物包围盒底边（纯页面坐标），拿来当真实脚底，别再猜屏高比例 */
    private var footPageY = 0

    // 小窗口：原版菜单里 about / howto / group 会另开一个 Electron BrowserWindow，
    // 这里用一个独立的悬浮 WebView 顶上，页面还是原版那几张 html。
    private var pageView: WebView? = null
    private var pageLp: WindowManager.LayoutParams? = null
    private var pageKind: String? = null

    // OTA：联网都在后台线程，这里只留状态机需要的最小共享数据
    private var otaBusy = false
    private var otaNotes = ""
    private var otaInfo: OtaUpdater.Info? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        // 用户主动收起过就别被残留心跳 / 开机广播拉起来（在写锁文件、建舞台之前拦住）
        if (!autoBoot(this) || !canOverlay(this)) {
            stopSelf()
            return
        }
        try {
            lockFile(this).writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createStage()
        scheduleKeepAlive(this)
        otaAutoStartup()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            cancelKeepAlive(this)
            // 先落盘再退，否则当前位置和形态会丢
            saveNow()
            handler.postDelayed({ stopSelf() }, 350)
            return START_NOT_STICKY
        }
        if (!autoBoot(this)) {
            // 收起之后任何残留的拉起请求都直接自停，不重建舞台
            cancelKeepAlive(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CMD) {
            val cmd = intent.getStringExtra(EXTRA_CMD)
            if (!cmd.isNullOrEmpty()) evalCmd(cmd)
        }
        if (intent?.action == ACTION_SETTINGS) evalSettings(0)
        if (intent?.action == ACTION_DOCK) applyDockOffset(true)
        if (intent?.action == ACTION_FRONT) {
            controlFront = intent.getBooleanExtra(EXTRA_FRONT, false)
            applyBoxZone()
        }
        if (intent?.action == ACTION_SOUND) {
            val id = intent.getStringExtra(EXTRA_SOUND)
            if (!id.isNullOrEmpty()) {
                web?.evaluateJavascript(
                    "typeof Sound!=='undefined'&&Sound.play(" + org.json.JSONObject.quote(id) + ")", null
                )
            }
        }
        if (intent?.action == ACTION_OTA) {
            val act = intent.getStringExtra(EXTRA_OTA)
            if (!act.isNullOrEmpty()) onOtaAction(act)
        }
        // 每次被拉起来都续下一次心跳；服务被杀后由闹钟重新拉起
        scheduleKeepAlive(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(longPress)
        dockPoll?.let { handler.removeCallbacks(it) }
        dockPoll = null
        dockAnim?.cancel()
        dockAnim = null
        try {
            menu?.hide()
        } catch (_: Exception) {
        }
        menu = null
        try {
            pageView?.let { wm.removeViewImmediate(it) }
        } catch (_: Exception) {
        }
        pageView = null
        pageLp = null
        pageKind = null
        try {
            hitView?.let { wm.removeViewImmediate(it) }
        } catch (_: Exception) {
        }
        hitView = null
        val v = web
        web = null
        try {
            if (v != null) wm.removeViewImmediate(v)
        } catch (_: Exception) {
        }
        try {
            v?.stopLoading()
            v?.destroy()
        } catch (_: Exception) {
        }
        try {
            lockFile(this).delete()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 窗口

    private fun createStage() {
        val dm = resources.displayMetrics
        // 舞台铺满整屏但「不接触摸」：点击全部透传给下面的应用，交互交给独立的命中区窗口
        val w = dm.widthPixels
        // 底部留 44px：原版的"地面"就是画布下沿，留白后宠物脚不会被屏幕边缘切掉
        val h = dm.heightPixels - 44
        stageW = w
        stageH = h
        stageOriginX = 0
        stageOriginY = 0

        val view = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = AssetClient()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                    Log.i(WEB_TAG, "${m?.message()} @${m?.sourceId()}:${m?.lineNumber()}")
                    return true
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = false
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            addJavascriptInterface(Host(), "XiaoheiHost")
        }

        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = PetPrefs.stageX(this@PetService)
            // 往上抬 44px，让画布的"地面"落在屏幕下沿之上
            y = STAGE_INSET + PetPrefs.stageY(this@PetService)
        }

        web = view
        lp = params
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
            stopSelf()
            return
        }
        createHitZone()
        view.loadUrl("$ORIGIN/renderer/index.html")
        diag(2500)
    }

    // ---------------------------------------------------------------- 命中区

    private fun createHitZone() {
        val v = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnTouchListener { _, ev -> relayTouch(ev) }
        }
        hitView = v
        val p = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = -10000
            y = -10000
        }
        hitLp = p
        try {
            wm.addView(v, p)
        } catch (e: Exception) {
            Log.e(TAG, "hit zone add failed: ${e.message}")
        }
    }

    /** 命中区随 pet-box 移动；box 是页面坐标系（= 舞台内坐标） */
    private fun updateHitZone(bx: Int, by: Int, bw: Int, bh: Int) {
        val v = hitView ?: return
        val p = hitLp ?: return
        if (bw <= 0 || bh <= 0) {
            // 页面在播动作（吃、伸展、抛球…）时会连续十几秒报 null。
            // 这段时间宠物其实还在原地，所以保留最后一次的包围盒，
            // 否则动作一放，长按和拖动就都按不动了。
            if (boxW > 0) return
            p.x = -10000
            p.y = -10000
            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            return
        }
        boxW = bw
        footPageY = by + bh
        boxL = stageOriginX + bx
        boxT = stageOriginY + by
        boxR = boxL + bw
        boxB = boxT + bh
        if (menuMode) return   // 菜单开着时命中区是全屏，别被 pet-box 拽回去
        applyBoxZone()
    }

    /** 按最近一次的宠物包围盒（+外扩）摆好命中区 */
    private fun applyBoxZone() {
        val v = hitView ?: return
        val p = hitLp ?: return
        // 小窗口开着 / 控制页在前台：命中区整块收走，别吃掉下层应用的触摸。
        // 后者很重要——宠物飘到右边时会盖住控制页的 ＋、常驻设置等按钮。
        if (pageView != null || controlFront) {
            p.x = -10000
            p.y = -10000
            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            return
        }
        // 只要盖住宠物本体 + 头顶气泡：留太大就会白吃掉下层应用的触摸
        val padX = 46
        val padTop = 96
        val padBottom = 24
        val x0 = (boxL - stageOriginX - padX).coerceAtLeast(0)
        val y0 = (boxT - stageOriginY - padTop).coerceAtLeast(0)
        val x1 = (boxR - stageOriginX + padX).coerceAtMost(stageW)
        val y1 = (boxB - stageOriginY + padBottom).coerceAtMost(stageH)
        p.x = stageOriginX + x0
        p.y = stageOriginY + y0
        p.width = (x1 - x0).coerceAtLeast(1)
        p.height = (y1 - y0).coerceAtLeast(1)
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    /**
     * menu2 是页面里的 DOM 面板，舞台窗口是 NOT_TOUCHABLE 的，手指根本碰不到它。
     * 所以菜单期间把命中区临时拉成全屏，让合成事件能落到面板上；关掉再收回去。
     */
    private fun setMenuMode(on: Boolean) {
        val v = hitView ?: return
        val p = hitLp ?: return
        menuMode = on
        menuGuard?.let { handler.removeCallbacks(it) }
        menuGuard = null
        if (on) {
            p.x = 0
            p.y = 0
            p.width = stageW
            p.height = stageH
            try { wm.updateViewLayout(v, p) } catch (e: Exception) { Log.w(TAG, "zone full fail: ${e.message}") }
            Log.i(TAG, "menu mode on, zone=${p.width}x${p.height}")
            // 兜底：脚本异常导致菜单一直不关时，3 分钟后强制收起（先让页面关面板，免得面板还在却点不到）
            val g = Runnable {
                if (menuMode) {
                    web?.evaluateJavascript("window.__menuClose && window.__menuClose()", null)
                    menuMode = false
                    applyBoxZone()
                }
            }
            menuGuard = g
            handler.postDelayed(g, 180000)
        } else {
            applyBoxZone()
        }
    }

    // ---------------------------------------------------------------- 小窗口

    /**
     * 原版菜单里 about / howto / group 是 main 进程另开的 Electron 窗口
     * （`popupWindow({file:'about.html'})`），页面本身只认 `ipcRenderer`。
     *
     * 安卓这边用一个独立的透明悬浮窗装 WebView，加载同一张 html；
     * 「主进程那半边」由 assets/web/renderer/pageshim.js 补上：
     * 数据本地给、关窗口/改高度/复制群号走 AndroidPage 桥。
     */
    private data class PageSpec(val url: String, val wDp: Int, val hDp: Int)

    /** 小窗口离屏幕边缘至少留这么多，卡片贴边会显得很挤 */
    private val PAGE_MARGIN = 16

    private fun pageSpec(id: String): PageSpec? = when (id) {
        "about" -> PageSpec("renderer/about.html", 520, 380)
        "howto" -> PageSpec("renderer/howto.html", 560, 440)
        "group" -> PageSpec("renderer/group.html", 560, 460)
        else -> null
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0"
    } catch (_: Exception) {
        "0.1.0"
    }

    private fun openPage(id: String) {
        val spec = pageSpec(id) ?: return
        closePage()
        val dm = resources.displayMetrics
        val w = (spec.wDp * dm.density).toInt().coerceAtMost(dm.widthPixels - 24)
        val h = (spec.hDp * dm.density).toInt().coerceAtMost(dm.heightPixels - PAGE_MARGIN * 2)

        val view = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            webViewClient = PageClient()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                    Log.i(WEB_TAG, "page: ${m?.message()} @${m?.lineNumber()}")
                    return true
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = false
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            // 页面只用到 post（关窗口/改高度/复制），别的一概不给
            addJavascriptInterface(PageHost(), "AndroidPage")
        }

        val p = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 原版 popupWindow 是按宠物区域居中的独立窗口，这里同样居中
            gravity = Gravity.CENTER
        }

        pageView = view
        pageLp = p
        pageKind = id
        try {
            wm.addView(view, p)
        } catch (e: Exception) {
            Log.e(TAG, "page addView failed: ${e.message}")
            pageView = null
            pageLp = null
            pageKind = null
            toast("小窗口打不开")
            return
        }
        Log.i(TAG, "page open $id ${w}x$h")
        // 卡片盖住的地方不该还能摸到宠物
        applyBoxZone()
        view.loadUrl("$ORIGIN/${spec.url}?v=" + appVersion())
    }

    private fun resizePage(h: Int) {
        val v = pageView ?: return
        val p = pageLp ?: return
        if (h <= 0) return
        val dm = resources.displayMetrics
        // 页面上报的是 CSS px（= dp），换成物理像素再设窗口高
        val nh = (h * dm.density).toInt().coerceIn(160, dm.heightPixels - PAGE_MARGIN * 2)
        if (p.height == nh) return
        p.height = nh
        try {
            wm.updateViewLayout(v, p)
        } catch (_: Exception) {
        }
        Log.i(TAG, "page resize -> ${p.width}x$nh")
    }

    private fun closePage() {
        val v = pageView ?: return
        val wasFace = pageKind == "face"
        pageView = null
        pageLp = null
        pageKind = null
        try { wm.removeView(v) } catch (_: Exception) {}
        try { v.destroy() } catch (_: Exception) {}
        // 大脸关掉之后桌面猫要回来
        if (wasFace) try { web?.visibility = View.VISIBLE } catch (_: Exception) {}
        Log.i(TAG, "page closed")
        applyBoxZone()
    }

    // ---------------------------------------------------------------- OTA 升级

    /**
     * 状态文件是唯一真相：控制页在主进程、关于页在另一个 WebView，都读同一份 JSON。
     * update 字段直接按 about.html 认识的 kind 写，页面逻辑一行都不用改。
     */
    private fun otaU(kind: String): org.json.JSONObject =
        org.json.JSONObject().put("kind", kind)

    private fun otaBase(): org.json.JSONObject {
        val old = OtaUpdater.readState(this)
        return org.json.JSONObject()
            .put("version", appVersion())
            .put("tag", "")
            .put("demo", false)
            .put("hasUpdater", true)
            .put("auto", OtaUpdater.autoEnabled(this))
            .put("notes", if (otaNotes.isNotEmpty()) otaNotes else old.optString("notes", ""))
    }

    private fun setOtaUpdate(u: org.json.JSONObject, extra: (org.json.JSONObject) -> Unit = {}) {
        val s = otaBase().put("update", u)
        extra(s)
        OtaUpdater.writeState(this, s)
        pushOtaState(s)
    }

    /** 关于页开着时把新状态推进去；没开就只写文件，页面下次 invoke('about-state') 会读到 */
    private fun pushOtaState(s: org.json.JSONObject) {
        if (pageKind != "about") return
        val v = pageView ?: return
        try {
            v.evaluateJavascript("window.__pageEmit&&window.__pageEmit('about',$s)", null)
        } catch (_: Exception) {
        }
    }

    private fun onOtaAction(act: String) {
        Log.i(TAG, "ota action $act")
        when (act) {
            "open-about" -> {
                openPage("about")
                otaCheck(false)
            }
            "check" -> otaCheck(true)
            "install" -> otaInstall()
            "show" -> Log.i(TAG, "ota notes shown in page")
            "auto-on", "auto-off" -> {
                OtaUpdater.setAuto(this, act == "auto-on")
                val s = OtaUpdater.readState(this).put("auto", OtaUpdater.autoEnabled(this))
                OtaUpdater.writeState(this, s)
                pushOtaState(s)
                toast(if (act == "auto-on") "已开启自动检查更新" else "已关闭自动检查更新")
            }
            "skip" -> {
                val v = otaInfo?.version ?: ""
                OtaUpdater.setSkipped(this, v)
                setOtaUpdate(otaU("skipped").put("version", v).put("skipped", true))
            }
            "unskip" -> {
                OtaUpdater.setSkipped(this, "")
                val v = otaInfo?.version ?: ""
                setOtaUpdate(otaU("available").put("version", v))
            }
            else -> Log.d(TAG, "ota action? $act")
        }
    }

    /** 开着自动更新时，每次启动后台查一次（6 小时内不重复），只下载不弹安装 */
    private fun otaAutoStartup() {
        if (!OtaUpdater.autoEnabled(this)) return
        if (System.currentTimeMillis() - OtaUpdater.lastCheck(this) < 6 * 60 * 60 * 1000L) return
        handler.postDelayed({ if (!destroyed) otaCheck(false, silent = true, autoDownload = true) }, 8000)
    }

    private fun otaCheck(force: Boolean, silent: Boolean = false, autoDownload: Boolean = false) {
        if (otaBusy) return
        if (!force && System.currentTimeMillis() - OtaUpdater.lastCheck(this) < 30 * 60 * 1000L) {
            val s = OtaUpdater.readState(this)
            if (s.has("update")) pushOtaState(s)
            return
        }
        otaBusy = true
        setOtaUpdate(otaU("checking"))
        Thread {
            try {
                val info = OtaUpdater.fetchManifest(this) ?: throw IllegalStateException("所有线路都没拿到版本信息")
                otaInfo = info
                otaNotes = info.notes
                OtaUpdater.markChecked(this)
                handler.post {
                    otaBusy = false
                    val cur = appVersion()
                    if (!OtaUpdater.isNewer(info.version, cur)) {
                        setOtaUpdate(otaU("latest")) { s -> s.put("checkedVersion", info.version) }
                        if (!silent) toast("已是最新版 v$cur")
                    } else {
                        val skipped = OtaUpdater.skippedVersion(this) == info.version
                        val u = otaU(if (skipped) "skipped" else "available")
                            .put("version", info.version)
                        if (skipped) u.put("skipped", true)
                        setOtaUpdate(u) { s -> s.put("checkedVersion", info.version) }
                        if (!silent) toast("发现新版 v${info.version}")
                        if (autoDownload && !skipped) otaDownloadOnly(info)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ota check failed: ${e.message}")
                handler.post {
                    otaBusy = false
                    setOtaUpdate(otaU("error").put("message", e.message ?: "连不上更新服务器"))
                }
            }
        }.start()
    }

    private fun otaDownloadOnly(info: OtaUpdater.Info) {
        otaBusy = true
        setOtaUpdate(otaU("downloading").put("version", info.version).put("percent", 0))
        Thread {
            val f = OtaUpdater.download(this, info) { pct, _, _ ->
                handler.post {
                    setOtaUpdate(otaU("downloading").put("version", info.version).put("percent", pct))
                }
            }
            handler.post {
                otaBusy = false
                if (f != null) {
                    setOtaUpdate(otaU("ready").put("version", info.version))
                    toast("新版 v${info.version} 已下载，打开关于页点「现在更新」安装")
                } else {
                    setOtaUpdate(otaU("available").put("version", info.version))
                }
            }
        }.start()
    }

    /** 「现在更新」：已经下好就直接装，没下好先下载再装 */
    private fun otaInstall() {
        if (otaBusy) return
        val cached = OtaUpdater.readState(this).optJSONObject("update")
        val ver = otaInfo?.version ?: cached?.optString("version") ?: ""
        if (ver.isEmpty()) {
            otaCheck(true)
            return
        }
        OtaUpdater.readyApk(this, ver)?.let {
            installReady(it, ver)
            return
        }
        val info = otaInfo
        if (info == null) {
            otaCheck(true)
            return
        }
        otaBusy = true
        setOtaUpdate(otaU("downloading").put("version", ver).put("percent", 0))
        Thread {
            val f = OtaUpdater.download(this, info) { pct, _, _ ->
                handler.post {
                    setOtaUpdate(otaU("downloading").put("version", ver).put("percent", pct))
                }
            }
            handler.post {
                otaBusy = false
                if (f == null) {
                    setOtaUpdate(otaU("error").put("message", "下载失败，所有线路都不通"))
                    toast("下载失败，换个网络再试")
                } else {
                    installReady(f, ver)
                }
            }
        }.start()
    }

    private fun installReady(f: File, ver: String) {
        setOtaUpdate(otaU("installing").put("version", ver))
        if (OtaUpdater.install(this, f)) {
            toast("正在安装 v$ver…")
        } else {
            setOtaUpdate(otaU("error").put("message", "这台设备没有可用的安装界面"))
            toast("找不到系统安装界面，请手动安装下载的 APK")
        }
    }

    // ---------------------------------------------------------------- 大脸猫

    /**
     * 「大脸猫」：整块屏幕当猫脸，只画眼睛和鼻子，不要脸的轮廓和耳朵。
     * 页面是 face.js 的纯矢量重画版，不加载图片，表情比原来的 9 帧位图更连贯。
     *
     * 不另开 Activity 是有原因的——这台 ROM 在离开前台 Activity 时会 force-stop 整个包，
     * 之前控制页就得召完立刻 finish() 才能让 :pet 活下来。所以这里还是贴一个全屏悬浮窗，
     * 盖住状态栏，同时把舞台藏起来，免得桌面那只小猫浮在大脸上面。
     */
    private fun openFace() {
        closePage()

        val view = WebView(this).apply {
            setBackgroundColor(Color.rgb(11, 11, 16))
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = PageClient()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                    Log.i(WEB_TAG, "face: ${m?.message()} @${m?.lineNumber()}")
                    return true
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = false
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            addJavascriptInterface(PageHost(), "AndroidPage")
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        pageView = view
        pageLp = p
        pageKind = "face"
        try {
            wm.addView(view, p)
        } catch (e: Exception) {
            Log.e(TAG, "face addView failed: ${e.message}")
            pageView = null
            pageLp = null
            pageKind = null
            toast("大脸打不开")
            return
        }
        // 大脸期间桌面猫让位（页面还在跑，只是不显示），关掉时 closePage 里恢复
        try { web?.visibility = View.INVISIBLE } catch (_: Exception) {}
        Log.i(TAG, "face open ${p.width}x${p.height}")
        applyBoxZone()
        view.loadUrl("$ORIGIN/renderer/face.html?v=" + appVersion())
    }

    private fun copyText(text: String) {
        if (text.isEmpty()) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("xiaohei", text))
            toast("已复制")
        } catch (_: Exception) {
        }
    }

    private inner class PageHost {
        @JavascriptInterface
        fun post(json: String) {
            handler.post { onPagePost(json) }
        }

        @JavascriptInterface
        fun copy(text: String) {
            handler.post { copyText(text) }
        }

        /** 关于页打开时同步拿一份真实状态：版本号、更新进度、自动更新开关、更新说明 */
        @JavascriptInterface
        fun aboutState(): String {
            val s = OtaUpdater.readState(this@PetService)
            if (!s.has("update")) s.put("update", org.json.JSONObject().put("kind", "latest"))
            s.put("version", appVersion())
            s.put("tag", "")
            s.put("demo", false)
            s.put("hasUpdater", true)
            s.put("auto", OtaUpdater.autoEnabled(this@PetService))
            return s.toString()
        }
    }

    private fun onPagePost(json: String) {
        try {
            val o = org.json.JSONObject(json)
            when (o.optString("k")) {
                "close" -> closePage()
                "size" -> resizePage(o.optInt("v"))
                "copy" -> copyText(o.optString("v"))
                // 关于页里的 检查更新 / 现在更新 / 自动更新 / 看看更新了什么
                "ota" -> onOtaAction(o.optString("v"))
                else -> Log.d(TAG, "page post ${o.optString("k")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "page post bad: $json ${e.message}")
        }
    }

    /** 把命中区的触摸翻译成页面里的合成指针事件 */
    private fun relayTouch(ev: android.view.MotionEvent): Boolean {        val p = hitLp ?: return false
        val x = (p.x + ev.x - stageOriginX).toInt()
        val y = (p.y + ev.y - stageOriginY).toInt()
        val phase = ev.actionMasked

        // 原生兜底菜单开着：按一下空白就收掉，这一下不再交给页面
        if (menu?.visible == true) {
            if (phase == android.view.MotionEvent.ACTION_DOWN) {
                menu?.hide()
                return true
            }
            return true
        }

        // 长按已经消费了这次手势：跟着来的 UP 必须吞掉，
        // 否则页面会把它当成一次点击/松手，刚弹起来的菜单立刻被收起。
        if (longFired && (phase == android.view.MotionEvent.ACTION_UP ||
                    phase == android.view.MotionEvent.ACTION_CANCEL)) {
            longFired = false
            touchDown = false
            handler.removeCallbacks(longPress)
            return true
        }

        // menu2 面板期间只做一件事：把事件原样送进页面，别再做长按判定
        if (!menuMode) {
            when (phase) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchDown = true
                    longFired = false
                    downAt = System.currentTimeMillis()
                    downX = ev.rawX
                    downY = ev.rawY
                    downStageX = x
                    downStageY = y
                    handler.removeCallbacks(longPress)
                    handler.postDelayed(longPress, 650)
                    Log.d(TAG, "touch down $downStageX,$downStageY menuMode=$menuMode")
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!touchDown) return true
                    if (Math.hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()) > 40) {
                        handler.removeCallbacks(longPress)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (!touchDown) return true
                    touchDown = false
                    // 已经弹过菜单：这一下算菜单的，别让页面再收一次 up 去触发单击
                    if (longFired) {
                        longFired = false
                        return true
                    }
                    // 原版只有少数动作挂了音效 CUE，还常被 "click -> busy / wake" 直接忽略，
                    // 所以点一下经常是静音的。这里给「真·轻点」补一声即时猫叫：
                    // 短按、没拖动、2 秒节流，避免连点刷屏或和拖拽/长按打架。
                    if (phase == android.view.MotionEvent.ACTION_UP &&
                        System.currentTimeMillis() - downAt < 650 &&
                        Math.hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()) <= 40 &&
                        System.currentTimeMillis() - lastTapMeow > 2000 &&
                        soundOn(this)
                    ) {
                        lastTapMeow = System.currentTimeMillis()
                        playSound(this, "cat_meow")
                    }
                }
            }
        }

        val type = when (phase) {
            android.view.MotionEvent.ACTION_DOWN -> "down"
            android.view.MotionEvent.ACTION_MOVE -> "move"
            android.view.MotionEvent.ACTION_UP -> "up"
            android.view.MotionEvent.ACTION_CANCEL -> "up"
            else -> return false
        }
        val js = "window.__synth && window.__synth('" + type + "'," + x + "," + y + ")"
        web?.evaluateJavascript(js, null)
        return true
    }

    /**
     * 长按到点：先让页面松手（它会进入 drag 态），再走原版的右键入口。
     * 原版右键 = openMenu() -> ipcRenderer.send('menu', menuState()) -> 主进程 openMenu2()；
     * 这里 send('menu') 被 shim 就地接管，直接调 Menu2.onMsg 把面板画起来。
     * 页面没有 openMenu（或没加载出 Menu2）时，shim 会回 menu-fallback，走原生面板兜底。
     */
    private fun onLongPress() {
        Log.d(TAG, "longPress fire touchDown=$touchDown destroyed=$destroyed")
        if (!touchDown || destroyed) return
        longFired = true
        web?.evaluateJavascript(
            "window.__synth && window.__synth('up'," + downStageX + "," + downStageY + ")", null
        )
        setMenuMode(true)
        web?.evaluateJavascript("typeof openMenu") { r ->
            if (r != null && r.contains("function")) {
                web?.evaluateJavascript("openMenu()", null)
            } else {
                Log.w(TAG, "no openMenu in page, native menu")
                setMenuMode(false)
                showNativeMenu()
            }
        }
    }

    /** 兜底：原版菜单没跑起来时用原生面板顶上 */
    private fun showNativeMenu() {
        val m = menu ?: PetMenu(this, wm, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            .also { menu = it }
        m.show(boxL, boxT, boxR, boxB) { cmd -> onMenuPick(cmd) }
        Log.d(TAG, "native menu at $boxL,$boxT,$boxR,$boxB")
    }

    private fun onMenuPick(cmd: String) {
        when (cmd) {
            "@settings" -> try {
                startActivity(
                    Intent(this, PetLauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            "@hide" -> {
                saveNow()
                handler.postDelayed({ stopSelf() }, 350)
            }
            else -> evalCmd(cmd)
        }
    }

    /**
     * menu2 里那些「主进程动作」的 id（原版 ACT 表里不是发指令、而是打开某个窗口/退出的那几个）。
     * 手机上能对上的对应到本地能力，对不上的就记一笔。
     */
    private fun onShellAction(id: String) {
        Log.i(TAG, "menu shell $id")
        when (id) {
            "set" -> onMenuPick("@settings")
            // 「躲起来」原来等同退出，现在改成「大脸猫」：整屏一张猫脸
            "hide" -> openFace()
            "quit" -> onMenuPick("@hide")
            // 原版这几个是另开 Electron 小窗口，现在换成独立的悬浮 WebView，装的还是原版 html
            "about" -> { openPage("about"); otaCheck(false) }
            "howto" -> openPage("howto")
            "group" -> openPage("group")
            // 许愿墙要连作者服务器、还要传图，手机版没做，给个明确提示别让人以为点了没反应
            "wish" -> toast("许愿墙要连作者的服务器，手机版暂时没做")
            // 菜单「检查更新」直接打开关于页并强制联网查一次
            "update" -> { openPage("about"); otaCheck(true) }
            // 原版「找文件」是 Windows 资源管理器那套，手机上改成回桌面
            "find" -> goHome()
            else -> Log.i(TAG, "shell action not supported yet: $id")
        }
    }

    /**
     * 「回到主页」= 按一次 Home 键。
     * 先问系统「默认桌面是谁」再显式启动，避免桌面装了多个时弹出选择框。
     */
    private fun goHome() {
        try {
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val r = packageManager.resolveActivity(home, 0)
            val ai = r?.activityInfo
            if (ai != null) home.setClassName(ai.packageName, ai.name)
            startActivity(home)
            Log.i(TAG, "go home -> ${ai?.packageName ?: "default"}")
        } catch (e: Exception) {
            Log.w(TAG, "go home failed: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun firstJsonString(payload: String): String {
        val t = payload.trim()
        val s = t.indexOf('"')
        if (s < 0) return ""
        val e = t.indexOf('"', s + 1)
        return if (e > s) t.substring(s + 1, e) else ""
    }

    /** 首次启动把形态切到人形（原版默认是猫），之后由用户的双击/菜单自行切换 */
    /**
     * 首次启动把形态切到人形（原版 Forms.form 默认是 cat）。
     * 页面 ready 之前丢命令是静默的，所以这里轮询确认 ready 再发，只做一次。
     */
    private fun firstRunForm() {
        if (PetPrefs.firstDone(this)) return
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                if (destroyed || ++tries > 30) return
                web?.evaluateJavascript("(typeof ready!=='undefined'&&ready)?1:0") { v ->
                    if (v?.contains("1") == true && !destroyed) {
                        evalCmd("form human")
                        PetPrefs.setFirstDone(this@PetService)
                    } else {
                        handler.postDelayed(this, 400)
                    }
                }
            }
        }
        handler.postDelayed(r, 900)
    }

    /**
     * 把设置推给页面（原版由 Electron 主进程负责）。
     * 音效不塞进 __emit('settings')：那条链路会连带触发 Scene/Pet 的 applySettings，
     * 这里单独调 Sound.setSettings，互不干扰。soundCats 空对象 = 四个分类全开。
     *
     * 音量：原版 volume 默认 60、BANK 里每条 mix 多为 0.5，算出来只有 0.30，
     * 再乘系统媒体音量就几乎听不见（真机点击日志是 "[sound] cat_tilt vol 0.30"）。
     * 这里默认把总音量拉到 100，并把 mix 放大 1.7 倍封顶 1.0（猫叫 0.5→0.85），
     * 具体档位由控制页「小猫音量 −/＋」存在 files/pet_volume.level（默认 100），
     * 只在运行时调 BANK，不改原版 sound.js。__boost 防止 evalSettings 重复调用叠乘。
     *
     * 循环音效（呼噜 / 打呼）：原版 _loopBuf 在检测到 require 时用 fs.readFileSync，
     * 而 shim 的假 fs 只有内联 JSON，日志是 "fs miss .../fx_snore.wav -> ENOENT"，
     * 所以两条 wav 永远播不出来。这里换成 fetch + decodeAudioData 的浏览器路径。
     */
    private fun evalSettings(delay: Long) {
        handler.postDelayed({
            val scale = PetPrefs.bodyScale(this)
            val on = soundOn(this)
            val vol = volumeLevel(this)
            val js = "(function(){try{if(typeof Sound!=='undefined'){" +
                    "Sound.setSettings({soundOn:$on,volume:$vol,soundCats:{}});" +
                    "Object.keys(Sound.BANK||{}).forEach(function(k){var b=Sound.BANK[k];" +
                    "if(!b.__boost){b.__boost=1;b.mix=Math.min(1,(b.mix||0.5)*1.7);}});" +
                    "if(!Sound.__loopWeb){Sound.__loopWeb=1;Sound._loopBuf=function(id){" +
                    "var ctx=Sound._loopCtx();if(!ctx)return Promise.reject(new Error('no ctx'));" +
                    "if(!Sound._bufs[id]){var p=fetch(Sound.BANK[id].file)" +
                    ".then(function(r){return r.arrayBuffer();})" +
                    ".then(function(b){return ctx.decodeAudioData(b);});" +
                    "Sound._bufs[id]=p;" +
                    "p.catch(function(){if(Sound._bufs[id]===p)delete Sound._bufs[id];});}" +
                    "return Sound._bufs[id];};}" +
                    "}}catch(e){}" +
                    "window.__emit&&window.__emit('settings',{bodyScale:$scale});})()"
            web?.evaluateJavascript(js, null)
        }, delay)
    }

    /**
     * 把舞台窗口整体平移，让小猫形态站在「无障碍服务真识别出来的」dock 上沿。
     *
     * 为什么不改 Scene.ground()：真机实测 Pet.ground() 是
     * `this.perch?.y ?? World.ground()`，宠物站在固定 perch 上，
     * 改 ground 它纹丝不动。所以改窗口位置：窗口下移 v 时脚底落在
     * (页面内脚底 + v)，令它等于识别上沿 → v = ground - footPageY。
     *
     * footPageY 取页面实际上报的 pet-box 底边（真机约 749），不是早先按屏高
     * 比例猜的 554；按 554 算出来 v 只有 6px，猫其实还踩在 dock 下半截。
     *
     * 状态机：dock 显示 → 贴到图标顶 + 落脚微调；dock 收起（none）→ 保持上次
     * 识别高度，别让屏保时钟每秒一次的事件把猫上下抖；切到应用列表（apps）→
     * 爬回屏幕底部。700ms 连续位移动画 = 从当前位置"爬"上去，不是闪现。
     * 命中区的 stageOriginY 同步变化，拖动和长按仍然跟得上。
     */
    private fun applyDockOffset(animate: Boolean) {
        val scan = DockScan.read(this)
        val state = scan?.state ?: "none"
        val ground = DockScan.groundY(this)
        val foot = petFootY()
        val want = when {
            !dockEnabled(this) || !formCat -> 0
            state == "apps" -> 0
            ground != null -> ground - foot
            else -> 0
        }
        // 每秒轮询 / 重复 ACTION_DOCK 都会进来；动画正朝同一个目标爬时别重启，
        // 否则 ValueAnimator 被反复 cancel，从中间值重跑，看起来像一顿一顿的。
        if (dockAnim?.isRunning == true && want == dockTarget) return
        if (want == dockOffset && dockAnim?.isRunning != true) return
        dockTarget = want
        Log.i(TAG, "dock offset $dockOffset -> $want (cat=$formCat, state=$state, iconTop=${scan?.iconTop}, ground=$ground, foot=$foot)")
        dockAnim?.cancel()
        val from = dockOffset
        if (!animate) {
            setDockOffset(want)
            return
        }
        dockAnim = ValueAnimator.ofInt(from, want).apply {
            duration = 700
            addUpdateListener { a -> setDockOffset(a.animatedValue as Int) }
            start()
        }
    }

    /** 页面还没上报 pet-box 时退化成画布下沿（屏高 - 舞台留白） */
    private fun petFootY(): Int {
        val fallback = resources.displayMetrics.heightPixels - STAGE_INSET - 2
        return if (footPageY in 200..fallback + 24) footPageY else fallback
    }

    private fun setDockOffset(v: Int) {
        dockOffset = v
        stageOriginY = v
        val p = lp ?: return
        p.y = STAGE_INSET + PetPrefs.stageY(this) - v
        try {
            web?.let { wm.updateViewLayout(it, p) }
        } catch (_: Exception) {
        }
        applyBoxZone()
    }

    /** 形态由页面 Forms.isCat() 决定，长按菜单 / 自动变形也会切，所以轮询而不是只听控制页按钮 */
    private fun startDockPoll() {
        dockPoll?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (destroyed) return
                web?.evaluateJavascript(
                    "(typeof Forms!=='undefined'&&typeof Forms.isCat==='function'&&Forms.isCat())?1:0"
                ) { v ->
                    if (destroyed) return@evaluateJavascript
                    val cat = v?.trim()?.trim('"') == "1"
                    formCat = cat
                    // 每秒重算：页面刚上报 pet-box 时脚底真值才到位；
                    // want 没变就直接 return，不会重复启动动画
                    applyDockOffset(true)
                }
                handler.postDelayed(this, 1000)
            }
        }
        dockPoll = r
        handler.postDelayed(r, 800)
    }

    private fun evalCmd(cmd: String) {
        val js = "window.__emit && window.__emit('cmd', " + org.json.JSONObject.quote(cmd) + ")"
        web?.evaluateJavascript(js, null)
        Log.i(TAG, "cmd $cmd")
        // 原版每 30s 自己存一次；改了形态/状态后立刻补存一次，免得刚好被杀就丢
        handler.postDelayed({ saveNow() }, 2200)
    }

    /** 触发原版的 beforeunload -> sendSync('save-sync') 立即落盘 */
    private fun saveNow() {
        try {
            web?.evaluateJavascript("window.__saveNow && window.__saveNow()", null)
        } catch (_: Exception) {
        }
    }

    /** 临时诊断：把视口、画布、宠物坐标打到日志里 */
    /** 舞台尺寸 / 画布诊断，页面加载后打几次 */
    private fun diag(delay: Long) {
        handler.postDelayed({
            val js = "(function(){try{return JSON.stringify({iw:innerWidth,ih:innerHeight,dpr:devicePixelRatio," +
                    "cw:canvas.width,ch:canvas.height,cssw:canvas.clientWidth,cssh:canvas.clientHeight," +
                    "px:(typeof Pet!=='undefined'?Math.round(Pet.x):null),py:(typeof Pet!=='undefined'?Math.round(Pet.y):null)," +
                    "ready:(typeof ready!=='undefined'?ready:null)});}catch(e){return 'ERR '+e;}})()"
            web?.evaluateJavascript(js) { v -> Log.i(WEB_TAG, "diag $v") }
        }, delay)
    }

    private fun resize(w: Int, h: Int) {
        val params = lp ?: return
        val view = web ?: return
        if (w <= 0 || h <= 0) return
        if (params.width == w && params.height == h) return
        params.width = w
        params.height = h
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- 资源

    private inner class AssetClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            Log.i(WEB_TAG, "page finished $url")
            diag(600)
            diag(3000)
            diag(8000)
            firstRunForm()
            evalSettings(900)
            startDockPoll()
            // 无障碍服务已授权就立刻扫一次，不用等它 1.5s 的轮询
            DockWatchService.requestScan(this@PetService)
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            return serveAsset(request?.url)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // 页面里不该有外链跳转，全部拦掉，避免把舞台导航走
            return true
        }
    }

    /** 小窗口用的客户端：只负责喂 assets，不掺和舞台那套 diag / 首启逻辑 */
    private inner class PageClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            Log.i(TAG, "page loaded $url")
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            return serveAsset(request?.url)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
    }

    /** http://pet.local/... → assets/web/... */
    private fun serveAsset(uri: android.net.Uri?): WebResourceResponse? {
        if (uri == null || uri.host != "pet.local") return null
        var p = uri.path ?: "/"
        if (p.endsWith("/")) p += "index.html"
        val rel = "web$p"
        return try {
            val stream = assets.open(rel)
            val headers = HashMap<String, String>()
            headers["Access-Control-Allow-Origin"] = "*"
            headers["Cache-Control"] = "no-cache"
            WebResourceResponse(mimeOf(rel), encodingOf(rel), 200, "OK", headers, stream)
        } catch (e: Exception) {
            Log.w(WEB_TAG, "404 $rel")
            null
        }
    }

    private fun mimeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".ogg") -> "audio/ogg"
        path.endsWith(".wav") -> "audio/wav"
        path.endsWith(".mp3") -> "audio/mpeg"
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun encodingOf(path: String): String? = when {
        path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".json") -> "UTF-8"
        else -> null
    }

    // ---------------------------------------------------------------- 桥

    private inner class Host {
        @JavascriptInterface
        fun ipc(channel: String, payload: String) {
            handler.post { onIpc(channel, payload) }
        }

        @JavascriptInterface
        fun loadState(): String = PetPrefs.loadState(this@PetService)

        @JavascriptInterface
        fun saveState(json: String) {
            PetPrefs.saveState(this@PetService, json)
        }

        @JavascriptInterface
        fun setSize(w: Int, h: Int) {
            handler.post { resize(w, h) }
        }

        @JavascriptInterface
        fun log(msg: String) {
            Log.i(WEB_TAG, msg)
        }

        @JavascriptInterface
        fun quit() {
            handler.post { stopSelf() }
        }
    }

    private fun onIpc(channel: String, payload: String) {
        if (destroyed) return
        when (channel) {
            "save" -> {
                // payload 是 [snapshot]，取第一个元素原样落盘
                val json = unwrapFirst(payload)
                if (json.isNotEmpty()) PetPrefs.saveState(this, json)
            }
            "resize" -> {
                web?.let { v ->
                    val w = (v.width / resources.displayMetrics.density).toInt()
                    val h = (v.height / resources.displayMetrics.density).toInt()
                    resize(w, h)
                }
            }
            "pet-box" -> applyPetBox(payload)
            "menu-ui" -> setMenuMode(payload.contains("1"))
            "menu-fallback" -> {
                Log.w(TAG, "menu2 unavailable, fallback to native")
                setMenuMode(false)
                showNativeMenu()
            }
            "shell" -> onShellAction(firstJsonString(payload))
            "say", "pet-trace", "diag-click", "alive-pong", "menu-close", "bubble-click",
            "pet-down", "pet-drag", "hit", "vis-ping", "pointer-lost", "screen-shift" -> {
                Log.d(TAG, "ipc $channel ${payload.take(120)}")
            }
            else -> Log.d(TAG, "ipc? $channel ${payload.take(200)}")
        }
    }

    /** pet-box: [{"x":..,"y":..,"w":..,"h":..}] 或 [null] */
    private fun applyPetBox(payload: String) {
        val json = unwrapFirst(payload)
        if (json.isEmpty() || json == "null") {
            updateHitZone(0, 0, 0, 0)
            return
        }
        try {
            val o = org.json.JSONObject(json)
            updateHitZone(o.optInt("x"), o.optInt("y"), o.optInt("w"), o.optInt("h"))
        } catch (_: Exception) {
        }
    }

    /** 把 shim 传来的 `[ {...} ]` 数组拆出第一个元素；不是数组就原样返回 */
    private fun unwrapFirst(payload: String): String {
        val t = payload.trim()
        if (!t.startsWith("[")) return t
        val s = t.indexOfFirst { it == '{' }
        val e = t.lastIndexOf('}')
        return if (s in 0 until e) t.substring(s, e + 1) else ""
    }

    // ---------------------------------------------------------------- 通知

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "小黑桌宠", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, PetLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
