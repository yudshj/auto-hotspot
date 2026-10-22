package dev.autohotspot

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import moe.shizuku.api.BinderContainer
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * 进程级初始化：安排 Shizuku binder 在两个进程之间的流转。
 *
 * Shizuku 服务端只在它启动时、以及应用 uid 从不活跃变成活跃时，把 binder 推给 ShizukuProvider 所在的进程。
 * 实时监听（常驻服务）让 uid 一直活跃，主进程被系统回收后重新拉起时就再也等不到推送（真机上测到过）。
 * 所以 ShizukuProvider 放在常驻的 `:monitor` 进程：服务端推给它，它一直活着、一直拿着；
 * 主进程启动时向它要（[ShizukuProvider.requestBinderForNonProviderProcess] 同步调用 getBinder），
 * 之后服务端再推送（例如 Shizuku 重启）时它会广播给主进程。
 * 反过来，主进程手里有 binder 时也回存给 `:monitor`（[shareShizukuBinder]），防止 `:monitor` 单独重启后手里没有。
 */
class AutoHotspotApp : Application() {
    private val isMonitorProcess get() = getProcessName().endsWith(":monitor")

    // 在 attachBaseContext 里设置：它早于 ContentProvider 初始化。若到 onCreate 才设，Shizuku 的推送可能先到，
    // 这时 :monitor 收下 binder 却不会广播给主进程（真机上遇到过）
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        ShizukuProvider.enableMultiProcessSupport(isMonitorProcess)
    }

    override fun onCreate() {
        super.onCreate()
        if (!isMonitorProcess) requestShizukuBinder(this)
    }

    companion object {
        private const val TAG = "AutoHotspot"
        private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"

        /** 主进程：向 `:monitor` 里的 ShizukuProvider 要 binder（没有就等它之后广播过来） */
        fun requestShizukuBinder(c: Context) {
            runCatching { ShizukuProvider.requestBinderForNonProviderProcess(c.applicationContext) }
                .onFailure { Log.w(TAG, "request Shizuku binder failed", it) }
        }

        /** 主进程：手里有 binder 时回存给 `:monitor`（它已经有活的 binder 时会忽略） */
        fun shareShizukuBinder(c: Context) {
            val binder = runCatching { Shizuku.getBinder() }.getOrNull() ?: return
            if (!binder.pingBinder()) return
            runCatching {
                c.applicationContext.contentResolver.call(
                    Uri.parse("content://${c.packageName}.shizuku"), "sendBinder", null,
                    Bundle().apply { putParcelable(EXTRA_BINDER, BinderContainer(binder)) },
                )
            }.onFailure { Log.w(TAG, "share Shizuku binder failed", it) }
        }
    }
}
