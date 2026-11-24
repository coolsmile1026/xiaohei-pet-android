package com.deepfish.xiaohei

import android.content.Context

/**
 * 页面存档（原版存在 userData/save.json，这里换成 SharedPreferences）。
 * 内容就是渲染层自己 snapshot() 出来的 JSON，不解析，原样存取。
 */
object PetPrefs {
    private const val SP = "xiaohei_pet"
    private const val KEY_STATE = "state"
    private const val KEY_X = "stage_x"
    private const val KEY_Y = "stage_y"

    fun loadState(c: Context): String =
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_STATE, "{}") ?: "{}"

    fun saveState(c: Context, json: String) {
        // commit 而不是 apply：独立进程随时可能被杀，必须立刻落盘
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY_STATE, json).commit()
    }

    fun bodyScale(c: Context): Float =
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).getFloat("body_scale", 1.3f)

    fun setBodyScale(c: Context, v: Float) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putFloat("body_scale", v).commit()
    }

    /** 首次启动是否已经处理过（只做一次「默认站人形」） */
    fun firstDone(c: Context): Boolean =
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("first_done", false)

    fun setFirstDone(c: Context) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean("first_done", true).commit()
    }

    fun stageX(c: Context): Int = c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt(KEY_X, 0)

    fun stageY(c: Context): Int = c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt(KEY_Y, 0)

    fun setStage(c: Context, x: Int, y: Int) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putInt(KEY_X, x).putInt(KEY_Y, y).commit()
    }
}
