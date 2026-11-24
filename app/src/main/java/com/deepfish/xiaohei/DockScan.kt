package com.deepfish.xiaohei

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * dock 识别结果的共享层。
 *
 * 无障碍服务跑在 :pet 进程，控制页跑在主进程，两边都只认 filesDir 里的这份 JSON：
 * 和音效开关 / 音量一样，不用 SharedPreferences（跨进程缓存读不到刚写的值）。
 * 写入用「临时文件 + rename」，避免控制页读到写了一半的内容。
 */
object DockScan {
    const val FILE = "pet_dock.scan"

    /** 面板上沿比图标顶再高一点，实测 1280x800 的 Aerial 是 586(图标顶) → 560(面板上沿) */
    const val TRIM_DEFAULT = -26
    const val TRIM_MIN = -80
    const val TRIM_MAX = 80

    data class Result(
        val state: String,      // dock=停靠着 5 图标 / apps=应用列表 / none=桌面静置
        val iconTop: Int,       // 最近一次真实识别到的图标顶边 y（不是估算值）
        val count: Int,
        val labels: String,
        val at: Long
    )

    fun read(c: Context): Result? = try {
        val f = File(c.filesDir, FILE)
        if (!f.exists()) null
        else JSONObject(f.readText()).let {
            Result(
                it.optString("state", "none"),
                it.optInt("iconTop", 0),
                it.optInt("count", 0),
                it.optString("labels", ""),
                it.optLong("at", 0)
            )
        }
    } catch (_: Exception) {
        null
    }

    fun write(c: Context, state: String, iconTop: Int, count: Int, labels: String) {
        try {
            val json = JSONObject()
                .put("state", state)
                .put("iconTop", iconTop)
                .put("count", count)
                .put("labels", labels)
                .put("at", System.currentTimeMillis())
                .toString()
            val tmp = File(c.filesDir, "$FILE.tmp")
            tmp.writeText(json)
            tmp.renameTo(File(c.filesDir, FILE))
        } catch (_: Exception) {
        }
    }

    /** 落脚微调：识别到的图标顶 + trim = 猫脚要落到的 y */
    fun trim(c: Context): Int = try {
        File(c.filesDir, "pet_dock.trim").readText().trim().toIntOrNull()
            ?.coerceIn(TRIM_MIN, TRIM_MAX) ?: TRIM_DEFAULT
    } catch (_: Exception) {
        TRIM_DEFAULT
    }

    fun setTrim(c: Context, v: Int) {
        try {
            File(c.filesDir, "pet_dock.trim").writeText(v.coerceIn(TRIM_MIN, TRIM_MAX).toString())
        } catch (_: Exception) {
        }
    }

    /** 当前识别结果算出来的落脚 y；没识别到就返回 null */
    fun targetY(c: Context): Int? {
        val r = read(c) ?: return null
        if (r.state != "dock" || r.iconTop <= 0) return null
        return (r.iconTop + trim(c)).coerceIn(200, 780)
    }

    /** 最近一次识别值（不管 dock 此刻是否还显示），用于 dock 收起时保持位置 */
    fun groundY(c: Context): Int? {
        val r = read(c) ?: return null
        if (r.iconTop <= 0) return null
        return (r.iconTop + trim(c)).coerceIn(200, 780)
    }
}
