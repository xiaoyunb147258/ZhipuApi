package dev.zhipu2api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.zhipu2api.store.SettingsStore
import dev.zhipu2api.util.Logger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsStore(context)
        if (!settings.autoStart) return
        try {
            val svc = Intent(context, GatewayService::class.java).apply {
                action = GatewayService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            Logger.log("开机自启：网关已拉起")
        } catch (e: Exception) {
            Logger.log("开机自启失败：${e.message}")
        }
    }
}
