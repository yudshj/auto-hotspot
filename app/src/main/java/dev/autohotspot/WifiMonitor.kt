package dev.autohotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
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
 * 监控方式（没有常驻服务，全部由系统事件唤醒）：
 *
 * 1. 「Wi-Fi 连上」：ConnectivityManager.registerNetworkCallback(request, PendingIntent)。
 *    注意：系统每投递一次 PendingIntent，约 5 秒后就会自动释放这条注册
 *    （ConnectivityService.onSendFinished → releasePendingNetworkRequestWithDelay），
 *    而且在 Wi-Fi 已连接时注册会立即再投递一次 —— 所以不能在收到后马上重新注册（会死循环）。
 *
 * 2. 「Wi-Fi 断开、默认网络变成移动数据」：JobScheduler 的网络约束针对应用的「默认网络」判断。
 *    连着 Wi-Fi 时安排一个「需要蜂窝网络」的任务，Wi-Fi 下无法满足，Wi-Fi 一断就会运行。
 *    （PendingIntent 网络回调只在「可用」时触发，收不到断开；本机蜂窝网络在连着 Wi-Fi 时也带
 *    FOREGROUND，也无法用「蜂窝变前台」判断。）
 *
 * 两者接力：Wi-Fi 连上 → 处理 + 安排「等移动数据」任务；任务运行（Wi-Fi 已断）→ 处理 + 重新注册 Wi-Fi 监听。
 * 另有一个 15 分钟的周期任务兜底（例如 Wi-Fi 断开时没有移动网络，蜂窝任务就不会运行）。
 *
 * 不用常驻前台服务的原因：Shizuku 服务端只在应用 uid「启动/从缓存恢复」时推送 binder，
 * 常驻前台服务会让 uid 一直处于活跃状态，Shizuku 晚于本应用启动（如开机）时就永远收不到 binder。
 */
object WifiMonitor {
    private const val ALERT_CHANNEL_ID = "alerts"
    private const val ALERT_NOTIF_ID = 2
    private const val KEY_LAST_SSID = "last_ssid"
    const val JOB_WAIT_CELLULAR = 1
    const val JOB_PERIODIC = 2

    fun isActive(c: Context) = Prefs.isEnabled(c) || Prefs.isAutoOn(c)

    private fun pendingIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 0, Intent(c, WifiEventReceiver::class.java),
        // 系统需要往里面填 EXTRA_NETWORK，必须是 MUTABLE
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** 注册「Wi-Fi 可用」监听。若此刻已连着 Wi-Fi，系统会立即投递一次（相当于做一次检查）。 */
    private fun armWifiListener(c: Context) {
        c.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            pendingIntent(c)
        )
    }

    /** 按开关状态启用/停用监控（开关切换、开机、应用更新、打开界面时调用） */
    fun sync(c: Context) {
        val ctx = c.applicationContext
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (isActive(ctx)) {
            // 同一个 PendingIntent 重复注册会替换旧注册，不会重复
            armWifiListener(ctx)
            if (js.getPendingJob(JOB_PERIODIC) == null) {
                js.schedule(
                    JobInfo.Builder(JOB_PERIODIC, ComponentName(ctx, MonitorJobService::class.java))
                        .setPeriodic(15 * 60 * 1000L)
                        .setPersisted(true)
                        .build()
                )
            }
        } else {
            runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(pendingIntent(ctx)) }
            js.cancel(JOB_WAIT_CELLULAR)
            js.cancel(JOB_PERIODIC)
        }
    }

    /** 连着 Wi-Fi 时调用：安排一个 Wi-Fi 断开、默认网络变成移动数据时才会运行的任务 */
    private fun scheduleWaitForCellular(c: Context) {
        // 总是重新安排（同 ID 会替换旧任务）：若本任务正在运行时 Wi-Fi 又连上，也能留下新任务
        val js = c.getSystemService(JobScheduler::class.java)
        val builder = JobInfo.Builder(JOB_WAIT_CELLULAR, ComponentName(c, MonitorJobService::class.java))
            .setRequiredNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            )
        // 加急任务调度更及时；被拒（如配额不足）时退回普通任务
        val ok = runCatching { js.schedule(builder.setExpedited(true).build()) }.getOrNull()
        if (ok != JobScheduler.RESULT_SUCCESS) js.schedule(builder.setExpedited(false).build())
    }

    private fun wifiConnected(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } || currentSsid(c) != null
    }

    private fun defaultIsCellular(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun secs(t0: Long) = "%.1f".format((System.currentTimeMillis() - t0) / 1000.0)

    private fun waitForShizuku() {
        // 刚被拉起时 Shizuku 的 binder 是异步送达的，等一会儿
        val deadline = System.currentTimeMillis() + 8000
        while (!HotspotController.shizukuRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
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

    /** Wi-Fi 连上时调用：连着列表中的 Wi-Fi 且热点开着，就关掉热点。会阻塞，不要在主线程调用。 */
    fun onWifiAvailable(c: Context, network: Network?) {
        val ctx = c.applicationContext
        // 监听已被系统释放，靠「等移动数据」任务在 Wi-Fi 断开后重新注册
        scheduleWaitForCellular(ctx)

        val ssid = currentSsid(ctx, network)
        val sp = Prefs.sp(ctx)
        if (ssid != null && sp.getString(KEY_LAST_SSID, null) != ssid) {
            Prefs.appendLog(ctx, "已连接 Wi-Fi：$ssid")
        }
        sp.edit().putString(KEY_LAST_SSID, ssid ?: "?").apply()

        if (!Prefs.isEnabled(ctx)) return
        if (ssid == null || ssid !in Prefs.ssids(ctx)) return
        if (!HotspotController.isHotspotEnabled(ctx)) return
        Notifier.progress(ctx, "正在关闭热点…", "已连接「$ssid」")
        waitForShizuku()
        val t0 = System.currentTimeMillis()
        try {
            val msg = HotspotController.stopHotspot(ctx)
            Prefs.appendLog(ctx, "[连接 $ssid] → $msg")
            Notifier.done(ctx, "热点已关闭", "已连接「$ssid」· 用时 ${secs(t0)} 秒")
            ctx.getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIF_ID)
        } catch (t: Throwable) {
            Notifier.clear(ctx)
            Prefs.appendLog(ctx, "[连接 $ssid] → 关闭失败：${t.message}")
            notifyFailure(ctx, "已连接 $ssid，但无法自动关闭热点", t.message ?: t.toString())
        }
    }

    /**
     * 默认网络变成移动网络时调用（Wi-Fi 刚断开）：开着「自动打开」就打开热点，然后重新注册 Wi-Fi 监听。
     * 只响应「Wi-Fi → 移动数据」这个切换事件，所以在流量下手动关掉热点后不会被反复打开。
     */
    fun onCellularDefault(c: Context) {
        val ctx = c.applicationContext
        try {
            val sp = Prefs.sp(ctx)
            val last = sp.getString(KEY_LAST_SSID, null) ?: return
            // 防抖：Wi-Fi 漫游/短暂掉线时不动作
            Thread.sleep(5000)
            if (wifiConnected(ctx) || !defaultIsCellular(ctx)) return
            sp.edit().remove(KEY_LAST_SSID).apply()
            Prefs.appendLog(ctx, "已断开 Wi-Fi：$last，改用移动数据")
            if (!Prefs.isAutoOn(ctx) || HotspotController.isHotspotEnabled(ctx)) return
            Notifier.progress(ctx, "正在打开热点…", "已断开「$last」，改用移动数据")
            waitForShizuku()
            val t0 = System.currentTimeMillis()
            try {
                val msg = HotspotController.startHotspot(ctx)
                Prefs.appendLog(ctx, "[改用移动数据] → $msg")
                Notifier.done(ctx, "热点已开启", "已断开「$last」，改用移动数据 · 用时 ${secs(t0)} 秒")
                ctx.getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIF_ID)
            } catch (t: Throwable) {
                Notifier.clear(ctx)
                Prefs.appendLog(ctx, "[改用移动数据] → 打开热点失败：${t.message}")
                notifyFailure(ctx, "已断开 $last，但无法自动打开热点", t.message ?: t.toString())
            }
        } finally {
            // 若 Wi-Fi 在防抖期间又连上了，注册时系统会立即投递，流程照常继续
            if (isActive(ctx)) armWifiListener(ctx)
        }
    }

    /** 周期兜底：没连 Wi-Fi 就确保 Wi-Fi 监听在；连着就确保「等移动数据」任务在 */
    fun onPeriodic(c: Context) {
        val ctx = c.applicationContext
        if (!isActive(ctx)) return
        if (wifiConnected(ctx)) scheduleWaitForCellular(ctx) else armWifiListener(ctx)
    }

    private fun notifyFailure(c: Context, title: String, detail: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "操作失败提醒", NotificationManager.IMPORTANCE_HIGH)
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
                .setContentTitle(title)
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
        if (!WifiMonitor.isActive(context)) return
        @Suppress("DEPRECATION")
        val network = intent.getParcelableExtra<Network>(ConnectivityManager.EXTRA_NETWORK)
        val pending = goAsync()
        thread {
            try {
                WifiMonitor.onWifiAvailable(context, network)
            } catch (t: Throwable) {
                Prefs.appendLog(context, "处理 Wi-Fi 事件出错：$t")
            } finally {
                pending.finish()
            }
        }
    }
}

/** JobScheduler 任务：默认网络变成蜂窝（Wi-Fi 断开）/ 周期兜底 */
class MonitorJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            try {
                when (params.jobId) {
                    WifiMonitor.JOB_WAIT_CELLULAR -> WifiMonitor.onCellularDefault(this)
                    WifiMonitor.JOB_PERIODIC -> WifiMonitor.onPeriodic(this)
                }
            } catch (t: Throwable) {
                Prefs.appendLog(this, "处理后台任务出错：$t")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
