package com.deepfish.xiaohei

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 长按菜单。
 *
 * 原版的菜单是主进程另开一个 BrowserWindow（renderer/menu2）画出来的，
 * 走的是 Electron 的鼠标钩子 + 图标面板那套。安卓这边没有必要为此再开一个 WebView：
 * 直接沿用原包 menu2/icons 里的同一批立绘，用原生悬浮窗画一块「奶白圆角面板 + 圆形图标 + 白药丸标签」，
 * 点中之后把原版的指令字符串 (toggleForm / sleep / ...) 发回渲染层，行为完全一致。
 */
class PetMenu(
    private val ctx: Context,
    private val wm: WindowManager,
    private val screenW: Int,
    private val screenH: Int
) {

    private data class Item(val label: String, val icon: String, val cmd: String)

    companion object {
        /** 与原版 MENU_ACTS 对齐；@ 开头的是壳层动作，不发给页面 */
        private val ITEMS = listOf(
            Item("变身", "morph.png", "toggleForm"),
            Item("摸摸", "hand.png", "hand"),
            Item("睡觉", "sleep.png", "sleep"),
            Item("醒醒", "wake.png", "wake"),
            Item("大小", "set.png", "@settings"),
            Item("收起", "compact.png", "@hide")
        )
        private const val ICON_DIR = "web/renderer/menu2/icons/"
        private const val CREAM = 0xF7FFF9F0.toInt()
        private const val INK = 0xFF2B1D12.toInt()
        private const val PEACH = 0xFFF7B777.toInt()
    }

    private var panel: View? = null
    private var lp: WindowManager.LayoutParams? = null
    var visible = false
        private set

    private fun dp(v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun icon(name: String): android.graphics.Bitmap? = try {
        ctx.assets.open(ICON_DIR + name).use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun roundRect(color: Int, radiusDp: Float, strokeDp: Float = 0f, strokeColor: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0f) d.setStroke(dp(strokeDp), strokeColor)
        return d
    }

    /** 贴到宠物包围盒外侧，左右都放不下才退化成夹紧 */
    fun show(petLeft: Int, petTop: Int, petRight: Int, petBottom: Int, onPick: (String) -> Unit) {
        hide()
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(CREAM, 22f, 1.5f, 0x443B2A1E)
            setPadding(dp(10f), dp(10f), dp(16f), dp(10f))
        }

        ITEMS.forEach { item ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
                isClickable = true
                isFocusable = false
            }

            val bubble = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(PEACH)
                    setStroke(dp(3f), Color.WHITE)
                }
            }
            bubble.addView(
                ImageView(ctx).apply {
                    setImageBitmap(icon(item.icon))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                },
                FrameLayout.LayoutParams(dp(56f), dp(56f))
            )

            val label = TextView(ctx).apply {
                text = item.label
                setTextColor(INK)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                background = roundRect(Color.WHITE, 99f, 1.2f, INK)
                setPadding(dp(13f), dp(4f), dp(13f), dp(4f))
            }

            row.addView(bubble, LinearLayout.LayoutParams(dp(60f), dp(60f)))
            row.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12f) })

            row.setOnClickListener {
                hide()
                onPick(item.cmd)
            }
            sheet.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // 先量一遍才知道往哪边放
        val ws = View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST)
        val hs = View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
        sheet.measure(ws, hs)
        val mw = sheet.measuredWidth
        val mh = sheet.measuredHeight

        // 先整块放到宠物右侧外面；右边不够就整块挪到左侧外面，都不行才夹紧
        var x = petRight + dp(12f)
        if (x + mw > screenW - dp(8f)) x = petLeft - mw - dp(12f)
        x = x.coerceIn(dp(8f), (screenW - mw - dp(8f)).coerceAtLeast(dp(8f)))
        val midY = (petTop + petBottom) / 2
        val y = (midY - mh / 2).coerceIn(dp(8f), (screenH - mh - dp(8f)).coerceAtLeast(dp(8f)))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        try {
            wm.addView(sheet, p)
        } catch (_: Exception) {
            return
        }
        panel = sheet
        lp = p
        visible = true
    }

    fun hide() {
        val v = panel ?: return
        panel = null
        lp = null
        visible = false
        try {
            wm.removeViewImmediate(v)
        } catch (_: Exception) {
        }
    }
}
