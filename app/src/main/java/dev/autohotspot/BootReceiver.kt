package dev.autohotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.isEnabled(context) || Prefs.isAutoOn(context)) {
            runCatching { WifiMonitor.sync(context) }
                .onFailure { Prefs.appendLog(context, "注册监听失败：${it.message}") }
        }
    }
}
