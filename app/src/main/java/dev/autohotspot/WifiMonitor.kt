package dev.autohotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlin.concurrent.thread

/**
 * 监控方式：ConnectivityManager.registerNetworkCallback(request, PendingIntent)。
 *
 * 这种注册由系统持有、进程死掉也有效；Wi-Fi 连上时系统发广播把应用拉起来。
 * 不用常驻前台服务的关键原因：Shizuku 服务端只在应用 uid「启动/从缓存恢复」时推送 binder，
 * 常驻前台服务会让 uid 一直处于活跃状态，Shizuku 晚于本应用启动（如开机）时就永远收不到 binder。
 * 每次被广播唤醒都是一次 uid 状态变化，Shizuku 会自动把 binder 发过来。
 */
object WifiMonitor {
    private const val ALERT_CHANNEL_ID = "alerts"
    private const val ALERT_NOTIF_ID = 2
    private const val KEY_LAST_SSID = "last_ssid"

    private fun pendingIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 0, Intent(c, WifiEventReceiver::class.java),
        // 系统需要往里面填 EXTRA_NETWORK，必须是 MUTABLE
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** 按开关状态注册/注销监听；注册后立即检查一次当前状态 */
    fun sync(c: Context) {
        val ctx = c.applicationContext
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val pi = pendingIntent(ctx)
        if (Prefs.isEnabled(ctx)) {
            // 同一个 PendingIntent 重复注册会替换旧注册，不会重复
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), pi
            )
            thread { check(ctx, null, "启用监控") }
        } else {
            runCatching { cm.unregisterNetworkCallback(pi) }
        }
    }

    fun currentSsid(c: Context, network: Network? = null): String? {
        val ctx = c.applicationContext
        if (network != null) {
            val caps = ctx.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)
            normalizeSsid((caps?.transportInfo as? WifiInfo)?.ssid)?.let { return it }
        }
        @Suppress("DEPRECATION")
        return normalizeSsid(ctx.getSystemService(WifiManager::class.java).connectionInfo?.ssid)
    }

    /** 若当前连着列表中的 Wi-Fi 且热点开着，就关掉热点。会阻塞（最多十几秒），不要在主线程调用。 */
    fun check(c: Context, network: Network?, reason: String) {
        val ctx = c.applicationContext
        val ssid = currentSsid(ctx, network)
        val sp = Prefs.sp(ctx)
        if (ssid != null && sp.getString(KEY_LAST_SSID, null) != ssid) {
            Prefs.appendLog(ctx, "已连接 Wi-Fi：$ssid")
        }
        sp.edit().putString(KEY_LAST_SSID, ssid).apply()

        if (ssid == null || ssid !in Prefs.ssids(ctx)) return
        if (!HotspotController.isHotspotEnabled(ctx)) return

        // 刚被拉起时 Shizuku 的 binder 是异步送达的，等一会儿
        val deadline = System.currentTimeMillis() + 8000
        while (!HotspotController.shizukuRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        try {
            val msg = HotspotController.stopHotspot(ctx)
            Prefs.appendLog(ctx, "[$reason] 匹配 $ssid → $msg")
            ctx.getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIF_ID)
        } catch (t: Throwable) {
            Prefs.appendLog(ctx, "[$reason] 匹配 $ssid → 关闭失败：${t.message}")
            notifyFailure(ctx, ssid, t.message ?: t.toString())
        }
    }

    private fun notifyFailure(c: Context, ssid: String, detail: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "关闭失败提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            c, 1, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hint = if (!HotspotController.shizukuRunning()) "Shizuku 未运行，请打开 Shizuku 启动服务。" else ""
        nm.notify(
            ALERT_NOTIF_ID,
            Notification.Builder(c, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hotspot_fg)
                .setContentTitle("已连接 $ssid，但无法自动关闭热点")
                .setContentText(hint.ifEmpty { detail })
                .setStyle(Notification.BigTextStyle().bigText("$hint\n$detail".trim()))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }
}

/** 接收系统的 Wi-Fi 网络可用通知（PendingIntent） */
class WifiEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.isEnabled(context)) return
        @Suppress("DEPRECATION")
        val network = intent.getParcelableExtra<Network>(ConnectivityManager.EXTRA_NETWORK)
        val pending = goAsync()
        thread {
            try {
                WifiMonitor.check(context, network, "Wi-Fi 连接")
            } catch (t: Throwable) {
                Prefs.appendLog(context, "处理 Wi-Fi 事件出错：$t")
            } finally {
                pending.finish()
            }
        }
    }
}
