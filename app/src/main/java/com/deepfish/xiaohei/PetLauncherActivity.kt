package com.deepfish.xiaohei

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * 控制页。召唤成功后立刻 finish()——这台 ROM 在离开前台 Activity 时会 force-stop 整个包，
 * 只有把前台让出去，独立进程 :pet 才能活下来（和悬浮猫那边同一个坑）。
 */
class PetLauncherActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var soundBtn: Button
    private lateinit var volText: TextView
    private lateinit var dockBtn: Button
    private lateinit var dockText: TextView
    private lateinit var a11yBtn: Button
    private lateinit var otaBtn: Button
    private var pendingPreview: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        status = findViewById(R.id.status)
        soundBtn = findViewById(R.id.soundBtn)
        volText = findViewById(R.id.volText)
        dockBtn = findViewById(R.id.dockBtn)
        dockText = findViewById(R.id.dockText)
        a11yBtn = findViewById(R.id.a11yBtn)
        otaBtn = findViewById(R.id.otaBtn)
        ensureNotificationPermission()

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            if (!PetService.canOverlay(this)) {
                Toast.makeText(this, R.string.overlay_hint, Toast.LENGTH_LONG).show()
                openOverlaySettings()
                return@setOnClickListener
            }
            PetService.show(this)
            status.text = getString(R.string.status_on)
            Toast.makeText(this, R.string.status_on, Toast.LENGTH_SHORT).show()
            closeSoon()
        }

        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            PetService.hide(this)
            updateStatus()
            closeSoon()
        }

        findViewById<Button>(R.id.permBtn).setOnClickListener { openOverlaySettings() }

        val formBtn = findViewById<Button>(R.id.formBtn)
        formBtn.setOnClickListener {
            // 原版的 toggleForm 是人 ⇄ 猫，不用自己记方向，也就不会和长按菜单的操作打架
            PetService.command(this, "toggleForm")
            // 变形动作本身没挂音效 CUE，补一声，免得点按钮像没反应
            PetService.playSound(this, "cat_meow")
            Toast.makeText(this, "切换形态", Toast.LENGTH_SHORT).show()
            closeSoon()
        }

        soundBtn.setOnClickListener {
            val next = !PetService.soundOn(this)
            PetService.setSoundOn(this, next)
            // 服务还活着就立刻重新下发，不用等下次开机；写文件保证重启后仍然生效
            PetService.pushSettings(this)
            updateStatus()
            Toast.makeText(this, if (next) "音效已开启" else "音效已关闭", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.volDownBtn).setOnClickListener { adjustVolume(-10) }
        findViewById<Button>(R.id.volUpBtn).setOnClickListener { adjustVolume(10) }
        volText.setOnClickListener { previewVolume() }

        dockBtn.setOnClickListener {
            val next = !PetService.dockEnabled(this)
            PetService.setDockEnabled(this, next)
            PetService.pushDock(this)
            if (next) DockWatchService.requestScan(this)
            updateStatus()
            Toast.makeText(
                this,
                if (next) "猫形态会贴着识别到的 dock 上沿活动" else "猫形态回到屏幕底部",
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<Button>(R.id.dockDownBtn).setOnClickListener { adjustDockTrim(-10) }
        findViewById<Button>(R.id.dockUpBtn).setOnClickListener { adjustDockTrim(10) }
        dockText.setOnClickListener {
            if (!DockWatchService.isEnabled(this)) {
                openAccessibilitySettings()
            } else {
                DockWatchService.requestScan(this)
                Toast.makeText(this, "已重新扫描 dock 图标", Toast.LENGTH_SHORT).show()
            }
        }
        a11yBtn.setOnClickListener { openAccessibilitySettings() }
        otaBtn.setOnClickListener {
            // 关于页是服务里的悬浮窗，服务没跑就先召唤（不然点了没反应）
            if (!PetService.isRunning(this)) {
                PetService.show(this)
                Toast.makeText(this, "已召唤小黑，正在检查更新", Toast.LENGTH_SHORT).show()
            }
            PetService.otaCommand(this, "open-about")
            closeSoon()
        }

        findViewById<Button>(R.id.keepBtn).setOnClickListener { openKeepAliveSettings() }
    }

    override fun onResume() {
        super.onResume()
        // 悬浮命中区盖在控制页上面时会把按钮点击吃掉，前台期间先把它移开
        PetService.setControlFront(this, true)
        // 从无障碍设置页回来时立刻刷新识别状态
        if (DockWatchService.isEnabled(this)) DockWatchService.requestScan(this)
        updateStatus()
    }

    override fun onPause() {
        PetService.setControlFront(this, false)
        super.onPause()
    }

    /** 这台 ROM 一离开前台就 force-stop 整个包，控制页做完事必须自己退掉 */
    private fun closeSoon() {
        handler.postDelayed({ if (!isFinishing) finish() }, 600)
    }

    /**
     * 音量档位：0..100，一次 10 档。写 filesDir 而不是 SharedPreferences——
     * 舞台在独立进程 :pet，SP 的进程内缓存读不到控制页刚写的值。
     * 改完让活着的服务立刻重发 settings，音量当场生效（下次启动也会读文件）。
     */
    private fun adjustVolume(delta: Int) {
        val next = (PetService.volumeLevel(this) + delta).coerceIn(0, 100)
        PetService.setVolumeLevel(this, next)
        PetService.pushSettings(this)
        updateStatus()
        if (PetService.soundOn(this)) {
            previewVolume()
        } else {
            Toast.makeText(this, "音效已静音，先点上方开关开启", Toast.LENGTH_SHORT).show()
        }
    }

    /** 连续点 −/＋ 时只试听最后一声，免得叠成一串猫叫；点中间数字也能试听 */
    private fun previewVolume() {
        pendingPreview?.let { handler.removeCallbacks(it) }
        val r = Runnable { if (!isFinishing) PetService.playSound(this, "cat_meow") }
        pendingPreview = r
        handler.postDelayed(r, 320)
    }

    /** 落脚微调：以无障碍服务读到的真实图标顶为基准，一次 10px，立刻缓动到新地面 */
    private fun adjustDockTrim(delta: Int) {
        val next = (DockScan.trim(this) + delta).coerceIn(DockScan.TRIM_MIN, DockScan.TRIM_MAX)
        DockScan.setTrim(this, next)
        PetService.pushDock(this)
        updateStatus()
        Toast.makeText(this, "落脚微调：图标顶 $next px", Toast.LENGTH_SHORT).show()
    }

    /**
     * 这台 TV ROM 裁掉了标准的 ACTION_ACCESSIBILITY_SETTINGS（resolveActivity 返回空），
     * 但保留了 AOSP 的 com.android.tv.settings/.MainSettings，无障碍在「设备偏好设置」里。
     * 所以按「标准入口 → TV 设置主页 → 通用设置」依次试，哪个能打开就用哪个。
     */
    private fun openAccessibilitySettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent().setComponent(
                ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")
            ),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in candidates) {
            val info = try {
                packageManager.resolveActivity(i, 0)?.activityInfo
            } catch (_: Exception) {
                null
            }
            // EmptyStubActivity 点了会直接回桌面，等于没开设置
            if (info == null || info.name.contains("Stub", true) || info.name.contains("Empty", true)) continue
            try {
                startActivity(i)
                Toast.makeText(this, R.string.dock_a11y_hint, Toast.LENGTH_LONG).show()
                closeSoon()
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(
            this,
            "请到 系统设置 → 设备偏好设置 → 无障碍 里开启「小黑 · dock 图标识别」",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateStatus() {
        val on = PetService.isRunning(this)
        val auto = PetService.autoBoot(this)
        val exempt = PetService.isBatteryExempt(this)
        val sound = PetService.soundOn(this)
        val vol = PetService.volumeLevel(this)
        val dockOn = PetService.dockEnabled(this)
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        val a11y = DockWatchService.isEnabled(this)
        val scan = DockScan.read(this)
        val ground = DockScan.groundY(this)
        val trim = DockScan.trim(this)
        soundBtn.text = if (sound) "音效：开（点此静音）" else "音效：关（点此开启）"
        volText.text = "小猫音量：$vol%（点此试听）"
        dockBtn.text = if (dockOn) "贴 dock：开" else "贴 dock：关"
        otaBtn.text = "版本 $ver · 检查更新（点此）"
        a11yBtn.text = getString(if (a11y) R.string.dock_a11y_on else R.string.dock_a11y_off)
        dockText.text = when {
            !a11y -> "未授权（点此开启）"
            scan == null || scan.iconTop <= 0 -> "等待识别（点此重扫）"
            scan.state == "dock" -> "识别 ${scan.count} 图标 · 落脚 ${ground}px（点此重扫）"
            scan.state == "apps" -> "应用列表 · 已回底部（点此重扫）"
            else -> "dock 收起 · 保持 ${ground}px（点此重扫）"
        }
        val dockStatus = when {
            !dockOn -> "已关闭"
            !a11y -> "待授权无障碍"
            ground == null -> "等待识别 dock 图标"
            else -> "已识别 图标顶 ${scan?.iconTop}px → 落脚 ${ground}px（微调 $trim）"
        }
        status.text = getString(if (on) R.string.status_on else R.string.status_off) +
                "\n开机自启：" + (if (auto) "已开启" else "已关闭（上次点了收起）") +
                "\n省电白名单：" + (if (exempt) "已加入" else "未加入，点下方按钮加入") +
                "\n音效：" + (if (sound) "已开启" else "已静音") +
                "\n小猫音量：$vol%" +
                "\ndock 上沿：$dockStatus" +
                "\n版本更新：v$ver · ${PetService.otaStatusLine(this)}" +
                (if (scan != null && scan.labels.isNotEmpty()) "\n识别图标：${scan.labels}" else "")
    }

    /** Android 13+ 不给通知权限，前台服务的常驻通知就不显示 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 常驻设置：优先弹「一键忽略电池优化」。
     * 有些 TV ROM（本机就是）把这个 action 解析成 EmptyStubActivity，点了直接回桌面、
     * 什么也没设置；检测到 Stub 就跳过，改开应用详情页手动关电池优化 / 开自启动。
     */
    private fun openKeepAliveSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !PetService.isBatteryExempt(this)) {
            val req = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            val target = try {
                packageManager.resolveActivity(req, 0)?.activityInfo?.name
            } catch (_: Exception) {
                null
            }
            val stub = target == null || target.contains("Stub", true) || target.contains("Empty", true)
            if (!stub) {
                try {
                    startActivity(req)
                    closeSoon()
                    return
                } catch (_: Exception) {
                }
            }
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, "请在电池 / 自启动里允许小黑常驻", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
        closeSoon()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
        }
    }
}
