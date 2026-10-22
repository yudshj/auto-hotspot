package dev.autohotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用更新后恢复监控（系统事件注册在重启或更新后会失效） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        // 开机算重新开始：上次记下的连接 / 没处理完的断开都作废
        if (boot) WifiMonitor.onBoot(context)
        if (!Prefs.isEnabled(context)) return
        runCatching { WifiMonitor.sync(context) }
            .onSuccess { if (boot) Prefs.appendLog(context, "开机：已恢复监控") }
            .onFailure { Prefs.appendLog(context, "注册监听失败：${it.message}") }
        if (boot) {
            // Shizuku 无 root 时开机不会自动运行；这里只记录状态，真正需要时会再等待并在失败时通知
            val pending = goAsync()
            Thread {
                try {
                    Thread.sleep(10_000) // Shizuku binder 是异步送达的，稍等再看
                    if (!HotspotController.shizukuRunning()) {
                        Prefs.appendLog(context, "开机：Shizuku 尚未运行，自动开关热点暂不可用")
                    }
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
