package com.deepfish.xiaohei

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 解锁 / 应用更新 / 探针广播时把舞台拉回来。
 *
 * 旧版这里先判 isRunning()（锁文件存在才恢复），但开机时服务本来就没在跑，
 * 等于永远不会自启。现在只看「用户有没有主动点过收起」(autoBoot) 和悬浮窗权限，
 * 开机就无条件拉起前台服务；用户主动收起过则尊重他的选择。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i("XiaoheiPet", "boot probe: $action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            PetService.ACTION_PROBE -> {
                if (!PetService.canOverlay(context)) {
                    Log.i("XiaoheiPet", "boot skip: no overlay permission")
                    return
                }
                if (!PetService.autoBoot(context)) {
                    Log.i("XiaoheiPet", "boot skip: user turned pet off")
                    return
                }
                PetService.show(context)
            }
        }
    }
}
