package com.acelite.hosts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启。
 *
 * 只有用户在前端打开了「开机自启」才会拉起服务。
 * 悬浮窗也一并恢复。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context?, intent: Intent?) {
        if (ctx == null) return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = ctx.applicationContext

        // 开机前如果是开启状态，才恢复
        if (Prefs.isProxyRunning(app)) {
            try {
                val i = Intent(app, GuardVpnService::class.java).apply {
                    action = GuardVpnService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(i)
                } else {
                    app.startService(i)
                }
            } catch (_: Exception) {}
        }

        // 悬浮窗
        if (Prefs.isFloatOn(app)) {
            try {
                app.startService(Intent(app, FloatBallService::class.java))
            } catch (_: Exception) {}
        }
    }
}