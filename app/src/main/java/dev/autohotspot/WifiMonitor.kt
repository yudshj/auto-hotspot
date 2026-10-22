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
import android.os.PowerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * 监控方式。默认还有一个「实时监听」常驻服务（[MonitorService]，独立进程）：网络一变就让主进程检查一次（[CheckReceiver]），
 * 下面这套由系统事件唤醒的机制照常保留，作为实时监听被关掉 / 被停止时的后备：
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
 * 另有一个 15 分钟的周期任务兜底（例如 Wi-Fi 断开时没有移动网络，蜂窝任务就不会运行），
 * 发现有没处理的断开时会补做一次断开处理。
 *
 * 连着没有互联网的 Wi-Fi 时（未验证，默认网络仍是移动数据），「等移动数据」任务会立即运行，
 * 这时改成每分钟检查一次（JOB_RECHECK），否则会每 5 秒空转一次。
 *
 * 流程本身（何时开/关热点、何时安排哪种唤醒）在 [AutoHotspotCore]，这里只提供 Android 实现。
 *
 * Shizuku binder：服务端只在它启动、或 uid 从不活跃变成活跃时推送。常驻服务让 uid 一直活跃，
 * 所以 binder 推给常驻的 `:monitor`（ShizukuProvider 在那里），主进程按需向它要，见 [AutoHotspotApp]。
 */
object WifiMonitor {
    private const val ALERT_CHANNEL_ID = "alerts"
    private const val ALERT_NOTIF_ID = 2
    private const val KEY_LAST_SSID = "last_ssid"
    private const val KEY_HANDLED_WIFI = "handled_wifi"
    const val JOB_WAIT_CELLULAR = 1
    const val JOB_PERIODIC = 2
    const val JOB_RECHECK = 3

    fun isActive(c: Context) = Prefs.isEnabled(c)

    private fun pendingIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 0, Intent(c, WifiEventReceiver::class.java),
        // 系统需要往里面填 EXTRA_NETWORK，必须是 MUTABLE
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /**
     * 「连着 Wi-Fi」的唯一判定：PendingIntent 监听、实时监听、快照都用这个请求（默认还要求 INTERNET 等能力）。
     * 开热点时系统会建一个只有 LOCAL_NETWORK、没有 INTERNET 能力的 Wi-Fi 网络（ap_br_wlan*），
     * 只看传输类型的话会把它当成「连上了 Wi-Fi」，刚开的热点随即被关掉（真机上出现过）。
     */
    fun wifiRequest(): NetworkRequest =
        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()

    private fun isWifiClient(cm: ConnectivityManager, network: Network) =
        cm.getNetworkCapabilities(network)?.let { wifiRequest().canBeSatisfiedBy(it) } == true

    /** 注册「Wi-Fi 可用」监听。若此刻已连着 Wi-Fi，系统会立即投递一次（相当于做一次检查）。 */
    private fun armWifiListener(c: Context) {
        c.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(wifiRequest(), pendingIntent(c))
    }

    /** 按开关状态启用/停用监控（开关切换、开机、应用更新、打开界面时调用） */
    fun sync(c: Context) {
        val ctx = c.applicationContext
        val js = ctx.getSystemService(JobScheduler::class.java)
        syncMonitor(ctx)
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
            js.cancel(JOB_RECHECK)
        }
    }

    /**
     * 按开关启动/停止实时监听。后台启动前台服务只在特定场合允许（开机、应用更新、界面在前台、在电池白名单里），
     * 不允许时抛异常；[quiet] = 周期任务里顺手尝试拉起，失败不记日志。
     */
    fun syncMonitor(c: Context, quiet: Boolean = false) {
        val ctx = c.applicationContext
        val intent = Intent(ctx, MonitorService::class.java)
        if (isActive(ctx) && Prefs.isRealtime(ctx)) {
            runCatching { ctx.startForegroundService(intent) }
                .onFailure { if (!quiet) Prefs.appendLog(ctx, "无法启动实时监听：${it.message}") }
        } else {
            ctx.stopService(intent)
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

    /** 过一会儿再检查一次（连着不是默认网络的 Wi-Fi 时用来发现它断开） */
    private fun scheduleRecheck(c: Context, delayMs: Long) {
        c.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(JOB_RECHECK, ComponentName(c, MonitorJobService::class.java))
                .setMinimumLatency(delayMs)
                .build()
        )
    }

    private fun snapshot(c: Context): DisconnectPolicy.Snapshot {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { isWifiClient(cm, it) }
        val defaultCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return DisconnectPolicy.Snapshot(
            wifiNetwork = wifi?.networkHandle,
            defaultIsCellular = defaultCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            ssid = currentSsid(c),
        )
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

    @Volatile
    private var core: AutoHotspotCore? = null

    /** 进程内唯一的流程实例（里面的锁要跨任务共享） */
    private fun core(c: Context): AutoHotspotCore =
        core ?: synchronized(this) {
            core ?: AutoHotspotCore(AndroidEnv(c.applicationContext)).also { core = it }
        }

    /** 开机时调用（在重新注册监听之前） */
    fun onBoot(c: Context) = core(c).onBoot()

    /** Wi-Fi 连上时调用。会阻塞，不要在主线程调用。 */
    fun onWifiAvailable(c: Context, network: Network?) {
        val ssid = currentSsid(c, network)
        runBlocking { core(c).onWifiAvailable(ssid, network?.networkHandle) }
    }

    /** 默认网络变成移动网络时调用（Wi-Fi 刚断开）。会阻塞。 */
    fun onCellularDefault(c: Context) = runBlocking { core(c).onCellularDefault() }

    /** 周期兜底 / 定时检查 / 实时监听通知网络变了（[realtime]）。会阻塞。 */
    fun onCheck(c: Context, realtime: Boolean = false) = runBlocking { core(c).onCheck(realtime) }

    private class AndroidEnv(private val ctx: Context) : AutoHotspotCore.Env {
        override val enabled get() = Prefs.isEnabled(ctx)
        override val anyWifi get() = Prefs.isAnyWifi(ctx)
        override val ssids get() = Prefs.ssids(ctx)

        override var lastSsid: String?
            get() = Prefs.sp(ctx).getString(KEY_LAST_SSID, null)
            set(v) {
                Prefs.sp(ctx).edit().apply { if (v == null) remove(KEY_LAST_SSID) else putString(KEY_LAST_SSID, v) }.apply()
            }

        override var handledWifi: Long?
            get() = Prefs.sp(ctx).let { if (it.contains(KEY_HANDLED_WIFI)) it.getLong(KEY_HANDLED_WIFI, 0) else null }
            set(v) {
                Prefs.sp(ctx).edit().apply { if (v == null) remove(KEY_HANDLED_WIFI) else putLong(KEY_HANDLED_WIFI, v) }.apply()
            }

        override fun snapshot() = snapshot(ctx)

        override fun wifiConnected(network: Long): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            return cm.allNetworks.any { it.networkHandle == network && isWifiClient(cm, it) }
        }
        override fun hotspotOn() = HotspotController.isHotspotEnabled(ctx)
        override suspend fun startHotspot() = HotspotController.startHotspot(ctx)
        override suspend fun stopHotspot() = HotspotController.stopHotspot(ctx)

        override suspend fun waitForShizuku() {
            // binder 由 :monitor 里的 ShizukuProvider 转过来（见 AutoHotspotApp）；进程启动时已经要过一次，没拿到就再要
            if (!HotspotController.shizukuRunning()) AutoHotspotApp.requestShizukuBinder(ctx)
            val deadline = System.currentTimeMillis() + 8000
            while (!HotspotController.shizukuRunning() && System.currentTimeMillis() < deadline) {
                delay(200)
            }
            AutoHotspotApp.shareShizukuBinder(ctx)
        }

        override fun scheduleWaitForCellular() = scheduleWaitForCellular(ctx)
        override fun armWifiListener() = armWifiListener(ctx)
        override fun scheduleRecheck(delayMs: Long) = scheduleRecheck(ctx, delayMs)
        override fun now() = System.currentTimeMillis()
        override fun log(msg: String) = Prefs.appendLog(ctx, msg)
        override fun progress(title: String, text: String) = Notifier.progress(ctx, title, text)
        override fun done(title: String, text: String) = Notifier.done(ctx, title, text)
        override fun clearProgress() = Notifier.clear(ctx)
        override fun failure(title: String, detail: String) = notifyFailure(ctx, title, detail)
        override fun clearFailure() = ctx.getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIF_ID)
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

/** JobScheduler 任务：默认网络变成蜂窝（Wi-Fi 断开）/ 周期兜底 / 定时检查 */
class MonitorJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            try {
                when (params.jobId) {
                    WifiMonitor.JOB_WAIT_CELLULAR -> WifiMonitor.onCellularDefault(this)
                    WifiMonitor.JOB_PERIODIC -> {
                        // 实时监听被停掉（例如用户在「活动应用」里停止）时顺手拉起来
                        WifiMonitor.syncMonitor(this, quiet = true)
                        WifiMonitor.onCheck(this)
                    }
                    WifiMonitor.JOB_RECHECK -> WifiMonitor.onCheck(this)
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

/** 实时监听（[MonitorService]，另一个进程）通知网络变了：在主进程里检查一次 */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!WifiMonitor.isActive(context)) return
        val pending = goAsync()
        // 防抖要等 5 秒，期间别让设备睡下去
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoHotspot:check")
            .apply { acquire(60_000) }
        thread {
            try {
                // 实时监听进程（重新）启动时也会发来一次：若它刚重启、手里没有 Shizuku binder，从主进程回存一份
                AutoHotspotApp.shareShizukuBinder(context)
                WifiMonitor.onCheck(context, realtime = true)
            } catch (t: Throwable) {
                Prefs.appendLog(context, "处理网络变化出错：$t")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pending.finish()
            }
        }
    }
}
