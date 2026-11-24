package com.deepfish.xiaohei

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * 真·dock 图标识别：读取 Aerial 屏保桌面（app.orchard.tv）的界面节点树，
 * 找出底部那一排可聚焦图标，用它们的真实 top 作为 dock 上沿的依据。
 *
 * 为什么必须走无障碍：dock 是 launcher 的 View，普通悬浮窗 / uiautomator 在屏保动画下
 * 拿不到稳定的窗口层级；只有 AccessibilityService 能持续读到节点树。
 * 只处理 app.orchard.tv，不碰美好家智能桌面，也不点击、不输入、不读取其它应用内容。
 *
 * 实测节点（1280x800，按下键呼出 dock）：
 *   5 个 android.view.View focusable，bounds 如 [75,586][271,696]，
 *   下面各有一个 TextView 标签（云视听小电视 / VidHub / Marco Movie / 当贝市场 / 汽水音乐）。
 *   当前有焦点那个会被 Compose 合并成稍大一圈的节点 [527,578][753,704]。
 * 应用列表则是同样规格的 3 行图标（top≈119 / 296 / 473），用「行数 >= 2」和
 * 「唯一一行且 top > 0.55*屏高」把两者区分开，避免把第三行应用图标错认成 dock。
 */
class DockWatchService : AccessibilityService() {

    companion object {
        private const val TAG = "XiaoheiDock"
        private const val AERIAL = "app.orchard.tv"
        private const val POLL_MS = 1500L
        const val ACTION_SCAN = "com.deepfish.xiaohei.DOCK_SCAN"

        /** 系统设置里是否把本服务打开了（控制页用来显示状态 / 引导授权） */
        fun isEnabled(c: Context): Boolean = try {
            val list = Settings.Secure.getString(
                c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            list.split(':').any {
                it.contains("com.deepfish.xiaohei") && it.contains("DockWatchService")
            }
        } catch (_: Exception) {
            false
        }

        /** 控制页点「重扫」：让服务立刻再读一次节点树 */
        fun requestScan(c: Context) {
            if (!isEnabled(c)) return
            try {
                c.startService(Intent(c, DockWatchService::class.java).setAction(ACTION_SCAN))
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastIconTop = 0
    private var lastLabels = ""
    private var lastPublished: String? = null

    private data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val cx: Int get() = (l + r) / 2
    }
    private data class Label(val top: Int, val text: String, val cx: Int, val width: Int)
    private data class Row(val anchorTop: Int, val boxes: MutableList<Box>) {
        /** 中位数：焦点图标被放大 8px 时不会被带偏 */
        val top: Int get() = boxes.map { it.t }.sorted()[boxes.size / 2]
        val left: Int get() = boxes.minOf { it.l }
        val right: Int get() = boxes.maxOf { it.r }
    }

    private val scanTask = Runnable { scanNow() }
    private val tick = object : Runnable {
        override fun run() {
            scanNow()
            if (!destroyed) handler.postDelayed(this, POLL_MS)
        }
    }
    private var destroyed = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        DockScan.read(this)?.let {
            lastIconTop = it.iconTop
            lastLabels = it.labels
        }
        Log.i(TAG, "service connected, lastIconTop=$lastIconTop")
        handler.postDelayed(tick, 400)
        scheduleScan(300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只在 Aerial 桌面的窗口变化 / 焦点变化时重扫；屏保时钟每秒也会触发一次，
        // 所以下面统一节流，防止把节点树重复跑几十遍。
        if (event?.packageName?.toString() != AERIAL) return
        scheduleScan(220)
    }

    override fun onInterrupt() {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCAN) {
            Log.i(TAG, "manual scan requested")
            lastPublished = null
            scheduleScan(60)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(scanTask)
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun scheduleScan(delay: Long) {
        handler.removeCallbacks(scanTask)
        handler.postDelayed(scanTask, delay)
    }

    /** 小黑被收起时不扫描，别让一个后台常驻服务白烧 CPU */
    private fun scanNow() {
        if (!PetService.isRunning(this)) return
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (root == null || root.packageName?.toString() != AERIAL) {
            publish("none", 0, 0, "")
            return
        }

        val boxes = ArrayList<Box>(48)
        val labels = ArrayList<Label>(24)
        try {
            collect(root, boxes, labels, 0)
        } catch (e: Exception) {
            Log.w(TAG, "collect failed: ${e.message}")
        }

        val rows = ArrayList<Row>()
        for (b in boxes.sortedBy { it.t }) {
            val last = rows.lastOrNull()
            if (last != null && abs(last.anchorTop - b.t) <= 24) last.boxes.add(b)
            else rows.add(Row(b.t, mutableListOf(b)))
        }
        val iconRows = rows.filter { r ->
            r.boxes.size in 3..10 &&
                    (r.right - r.left) >= sw * 0.5 &&
                    r.top > 0
        }

        val state = when {
            iconRows.size >= 2 -> "apps"                                   // 应用列表（3 行图标）
            iconRows.size == 1 && iconRows[0].top > sh * 0.55 -> "dock"  // 底部单排 dock
            else -> "none"                                                // 屏保静置 / 无图标
        }

        if (state == "dock") {
            val row = iconRows[0]
            val centers = row.boxes.map { it.cx }
            val ls = labels
                .filter { l ->
                    // 只要图标正下方的短标签：屏保壁纸的版权长文本也会被
                    // flagIncludeNotImportantViews 带进节点树，必须按长度、
                    // 宽度和水平对齐排掉，否则控制页会显示一串风景说明。
                    l.top >= row.top + 55 && l.top <= row.top + 210 &&
                            l.text.length <= 24 && l.width <= 320 &&
                            centers.any { abs(l.cx - it) <= 110 }
                }
                .sortedBy { it.cx }
                .map { it.text }
                .distinct()
                .take(8)
            publish("dock", row.top, row.boxes.size, ls.joinToString(" / "))
        } else {
            publish(state, 0, 0, "")
        }
    }

    private fun collect(
        n: AccessibilityNodeInfo,
        boxes: MutableList<Box>,
        labels: MutableList<Label>,
        depth: Int
    ) {
        if (depth > 20) return
        val r = Rect()
        try {
            n.getBoundsInScreen(r)
        } catch (_: Exception) {
            return
        }
        val w = r.width()
        val h = r.height()
        val visible = try {
            n.isVisibleToUser
        } catch (_: Exception) {
            true
        }
        // 图标 = 可聚焦、尺寸中等的方块。这个尺寸窗口顺带排除了右上角状态栏
        // ([1127,37][1224,82]，高 45) 和整屏容器。
        if (visible && n.isFocusable && w in 110..340 && h in 60..240 && r.top >= 0) {
            boxes.add(Box(r.left, r.top, r.right, r.bottom))
        }
        val cls = n.className?.toString() ?: ""
        val txt = try {
            n.text?.toString()?.trim()
        } catch (_: Exception) {
            null
        }
        if (visible && !txt.isNullOrEmpty() && cls.contains("TextView")) {
            labels.add(Label(r.top, txt, (r.left + r.right) / 2, r.width()))
        }
        for (i in 0 until n.childCount) {
            val child = try {
                n.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            collect(child, boxes, labels, depth + 1)
            try {
                child.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 只有「状态 / 图标顶」真的变了才去打扰舞台，否则屏保时钟每秒一次的事件
     * 会让 PetService 每秒重算一次窗口位置。none / apps 保留 lastIconTop，
     * 这样 dock 收起、重启后猫仍站在上次真实识别到的高度。
     */
    private fun publish(state: String, iconTop: Int, count: Int, labels: String) {
        if (state == "dock" && iconTop > 0) {
            // dock 弹出动画里图标 top 会在 585/586 之间跳一帧；差不到 3px 就别
            // 写新值、别通知舞台，否则猫会为 1px 重新启动一次 700ms 动画。
            if (lastIconTop == 0 || abs(iconTop - lastIconTop) >= 3) lastIconTop = iconTop
            if (labels.isNotEmpty()) lastLabels = labels
        }
        DockScan.write(
            this, state, lastIconTop, count,
            if (state == "dock") labels else lastLabels
        )
        val key = "$state:$lastIconTop"
        if (key == lastPublished) return
        lastPublished = key
        Log.i(TAG, "state=$state iconTop=$lastIconTop icons=$count labels=$lastLabels")
        if (PetService.isRunning(this) && PetService.dockEnabled(this)) {
            try {
                startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_DOCK))
            } catch (_: Exception) {
            }
        }
    }
}
