package dev.autohotspot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.random.Random

/**
 * 模拟的 Android 系统，用虚拟时间驱动真实的 [AutoHotspotCore]。
 *
 * 建模的系统行为（与 WifiMonitor 注释中的实测结论一致）：
 * - 默认网络：已验证的 Wi-Fi > 移动数据 > 未验证的 Wi-Fi。
 * - PendingIntent 网络回调：每次注册只投递一次（AOSP 的 mPendingIntentSent）——注册时已有 Wi-Fi 网络就立即投递，
 *   否则等下一个 Wi-Fi 网络出现；投递约 5 秒后系统按 PendingIntent 释放注册，期间重新注册过的也一起释放。
 * - 「等移动数据」任务：默认网络是移动数据时启动（有少量延迟），启动即消耗；
 *   运行中同 ID 重新安排会让系统把旧任务视为已停止（旧线程继续跑），新任务可以再次启动。
 * - 周期任务：每 15~30 分钟一次（持久化，重启后仍在）；定时检查任务：到点运行一次，同 ID 替换（不持久化）。
 * - 实时监听（常驻服务，独立进程）：「当前 Wi-Fi 连接 / 默认网络是不是移动数据」一变，主进程就做一次检查；不受 Doze 影响。
 *   由 sync()（开机、打开界面）启动；周期任务也会尝试拉起，但后台启动前台服务只有在电池白名单里才允许。
 * - connectionInfo 的 SSID：连上或正在关联/握手时都有值；没有位置权限时读不到。
 * - Doze：只影响不在电池优化白名单里的应用的普通任务（加急任务、广播不受影响），只在维护窗口里放行。
 * - 进程被杀（系统回收主进程 / 用户在「活动应用」里停止整个应用）：进行中的处理立刻中止、之后的写入不生效；
 *   系统里的登记（任务、网络回调注册）和已保存的数据都还在。用户停止还会停掉实时监听。
 * - 重启：热点关闭、进程内状态和非持久任务丢失、网络回调注册丢失；开机广播里重新注册；Shizuku 稍后才可用。
 */
internal class SimSystem(
    private val test: TestScope,
    val settings: Settings = Settings(),
    private val rnd: Random = Random(0),
) {
    data class Settings(
        /** 「自动开关热点」总开关 */
        val enabled: Boolean = true,
        val anyWifi: Boolean = true,
        val ssids: Set<String> = emptySet(),
        val canReadSsid: Boolean = true,
        /** 在电池优化白名单里（Doze 下普通任务也照常运行） */
        val batteryExempt: Boolean = true,
        /** 加急任务配额充足；不足时 schedule 退回普通任务 */
        val expeditedQuota: Boolean = true,
        /** 「实时监听」开关 */
        val realtime: Boolean = false,
    )

    data class Wifi(val id: Long, val ssid: String, var validated: Boolean)
    enum class Net { WIFI, CELL, NONE }

    // ---- 世界状态 ----
    var wifi: Wifi? = null; private set
    var wifiEnabled = true; private set
    /** 正在关联/握手的 SSID（还没有 Network） */
    var handshaking: String? = null; private set
    var dataEnabled = true; private set
    var cellSignal = true; private set
    var hotspot = false; private set
    var shizukuUp = true; private set
    var dozing = false; private set
    private var maintenance = false
    private var nextWifiId = 100L

    val cellularAvailable get() = dataEnabled && cellSignal
    fun defaultNet(): Net = when {
        wifi?.validated == true -> Net.WIFI
        cellularAvailable -> Net.CELL
        wifi != null -> Net.WIFI
        else -> Net.NONE
    }

    // ---- 应用在系统里的登记（重启才丢） ----
    private var system = newScope()
    private var listenerArmed = false
    private var listenerSent = false
    private var recheck: Job? = null
    private var cellJobPending = false
    private var cellJobExpedited = true
    private var cellStartScheduled = false
    var monitorRunning = false; private set
    private var monitorKey: Pair<Long?, Boolean>? = null

    // ---- 已保存的数据（SharedPreferences，进程被杀也在） ----
    private var storedLastSsid: String? = null
    private var storedHandledWifi: Long? = null

    // ---- 主进程 ----
    private var process = newScope()
    private var env = ProcessEnv()
    private var core = AutoHotspotCore(env)

    // ---- 记录（给断言用） ----
    data class Action(val time: Long, val on: Boolean)
    val appActions = mutableListOf<Action>()
    data class Run(val time: Long, val kind: String)
    val handlerRuns = mutableListOf<Run>()
    val logs = mutableListOf<String>()

    /** 每次 Wi-Fi 连接；[deliverable] = 连上那一刻应用能及时知道（实时监听在，或 PendingIntent 监听在） */
    data class Session(val id: Long, val ssid: String, val start: Long, val deliverable: Boolean) {
        var end: Long? = null
        /** 断开前 Wi-Fi 是否是默认网络（否 = 没有互联网、默认仍是移动数据） */
        var wasDefault = true
        override fun toString() = "#$id $ssid ${start / 1000}s~${end?.let { "${it / 1000}s" } ?: "now"}" +
            (if (deliverable) "" else " 监听不在") + (if (wasDefault) "" else " 非默认")
    }
    val sessions = mutableListOf<Session>()
    /** 应用按「连上」处理过的连接 */
    val handledIds = mutableSetOf<Long>()
    val failures = mutableListOf<String>()

    val time get() = test.testScheduler.currentTime

    /** 应用还有没处理完的断开（参考模型用来判断应用晚开热点是不是在补做） */
    val appPendingDisconnect get() = storedLastSsid != null && wifi == null

    // ================= 驱动 =================

    /** 首次安装/开机后：注册监听 + 启动周期任务 */
    fun start() {
        sync()
        startPeriodic()
    }

    fun advance(ms: Long) {
        test.testScheduler.advanceTimeBy(ms)
        test.testScheduler.runCurrent()
    }

    /** 挂在 backgroundScope 下，测试结束自动取消 */
    private fun newScope() = test.backgroundScope.let { CoroutineScope(it.coroutineContext + SupervisorJob(it.coroutineContext.job)) }

    // ================= 世界事件 =================

    fun connect(ssid: String, validated: Boolean = true) {
        check(wifiEnabled)
        endSession()
        handshaking = null
        val w = Wifi(nextWifiId++, ssid, validated)
        wifi = w
        sessions += Session(w.id, ssid, time, deliverable = monitorRunning || (listenerArmed && !listenerSent))
        pump()
    }

    /** 走远、信号丢失 */
    fun loseWifi() {
        endSession()
        pump()
    }

    private fun endSession() {
        if (wifi == null) return
        sessions.last().apply {
            end = time
            wasDefault = defaultNet() == Net.WIFI
        }
        wifi = null
    }

    fun startHandshake(ssid: String) {
        if (wifi == null && wifiEnabled) handshaking = ssid
        pump()
    }

    fun endHandshake() {
        handshaking = null
        pump()
    }

    fun validateWifi() {
        wifi?.validated = true
        pump()
    }

    fun setWifiEnabled(on: Boolean) {
        wifiEnabled = on
        if (!on) { endSession(); handshaking = null }
        pump()
    }

    fun setData(on: Boolean) { dataEnabled = on; pump() }
    fun setCellSignal(on: Boolean) { cellSignal = on; pump() }

    /** 用户手动开关热点（快捷设置） */
    fun userHotspot(on: Boolean) { hotspot = on; pump() }

    fun setShizuku(up: Boolean) { shizukuUp = up }

    fun setDozing(on: Boolean) {
        dozing = on
        pump()
    }

    /** 用户打开应用界面：onCreate 里调用 sync() */
    fun openApp() {
        if (core.isActive) sync()
    }

    /** 系统回收主进程（实时监听在另一个进程，不受影响） */
    fun killProcess() {
        process.cancel()
        process = newScope()
        env.alive = false
        env = ProcessEnv()
        core = AutoHotspotCore(env)
    }

    /** 用户在「活动应用」里停止整个应用：主进程和实时监听都停掉 */
    fun stopApp() {
        killProcess()
        monitorRunning = false
    }

    fun reboot(shizukuDelayMs: Long = 20_000) {
        killProcess()
        system.cancel()
        system = newScope()
        monitorRunning = false
        hotspot = false
        endSession() // 重启后 Wi-Fi 要重新连
        handshaking = null
        nextWifiId = 100 // Network 句柄重新分配
        handledIds.clear()
        listenerArmed = false
        listenerSent = false
        recheck = null
        cellJobPending = false // JOB_WAIT_CELLULAR 没有持久化
        cellStartScheduled = false
        dozing = false
        shizukuUp = false
        system.launch {
            delay(shizukuDelayMs)
            shizukuUp = true
        }
        system.launch {
            delay(3_000) // BOOT_COMPLETED
            core.onBoot()
            openApp() // BootReceiver 同样只在开关开着时 sync
        }
        startPeriodic()
    }

    // ================= 系统行为 =================

    /** WifiMonitor.sync()：注册 Wi-Fi 监听，按开关启动/停止实时监听 */
    private fun sync() {
        env.armWifiListener()
        if (settings.realtime) startMonitor() else monitorRunning = false
    }

    private fun startMonitor() {
        if (monitorRunning) return
        monitorRunning = true
        monitorKey = null // 注册回调时会立即收到当前状态
        pump()
    }

    private fun startPeriodic() {
        system.launch {
            while (true) {
                delay(rnd.nextLong(15 * 60_000L, 30 * 60_000L))
                while (blockedByDoze(expedited = false)) delay(10_000)
                // 周期任务里尝试拉起实时监听：后台启动前台服务只有在电池白名单里才允许
                if (settings.realtime && core.isActive && settings.batteryExempt) startMonitor()
                runHandler("periodic") { core.onCheck() }
            }
        }
        if (!settings.batteryExempt) startMaintenanceWindows()
    }

    private var maintenanceStarted = false
    private fun startMaintenanceWindows() {
        if (maintenanceStarted) return
        maintenanceStarted = true
        test.backgroundScope.launch {
            while (true) {
                delay(rnd.nextLong(20 * 60_000L, 60 * 60_000L))
                maintenance = true
                pump()
                delay(30_000)
                maintenance = false
            }
        }
    }

    private fun blockedByDoze(expedited: Boolean) =
        dozing && !maintenance && !settings.batteryExempt && !expedited

    /** 系统把应用拉起来跑一个入口（广播 / 任务）：在当时的主进程里运行 */
    private fun runHandler(kind: String, block: suspend (AutoHotspotCore) -> Unit) {
        handlerRuns += Run(time, kind)
        val c = core
        process.launch { block(c) }
    }

    /** 根据当前状态安排系统的反应 */
    private fun pump() {
        val w = wifi
        if (listenerArmed && !listenerSent && w != null) {
            listenerSent = true
            system.launch {
                delay(50)
                // 接收方解析 SSID：Network 还在就从它的 WifiInfo 读，否则退回 connectionInfo
                val ssid = when {
                    !settings.canReadSsid -> null
                    wifi?.id == w.id -> w.ssid
                    else -> supplicantSsid()
                }
                runHandler("wifi") { it.onWifiAvailable(ssid, w.id) }
            }
            system.launch {
                delay(5_000)
                listenerArmed = false
            }
        }
        if (cellJobPending && !cellStartScheduled && defaultNet() == Net.CELL && !blockedByDoze(cellJobExpedited)) {
            cellStartScheduled = true
            system.launch {
                delay(rnd.nextLong(20, 2_000))
                cellStartScheduled = false
                if (cellJobPending && defaultNet() == Net.CELL && !blockedByDoze(cellJobExpedited)) {
                    cellJobPending = false
                    runHandler("cellular") { it.onCellularDefault() }
                } else {
                    pump()
                }
            }
        }
        if (monitorRunning) {
            val key = wifi?.id to (defaultNet() == Net.CELL)
            if (key != monitorKey) {
                monitorKey = key
                system.launch {
                    delay(100) // 监听进程 → 广播 → 主进程
                    if (monitorRunning) runHandler("monitor") { it.onCheck(realtime = true) }
                }
            }
        }
    }

    private fun supplicantSsid() = wifi?.ssid ?: handshaking

    /** 一个主进程看到的系统。进程被杀后（[alive] = false）它的写入和动作都不再生效 */
    private inner class ProcessEnv : AutoHotspotCore.Env {
        var alive = true

        override val enabled get() = settings.enabled
        override val anyWifi get() = settings.anyWifi
        override val ssids get() = settings.ssids
        override var lastSsid: String?
            get() = storedLastSsid
            set(v) { if (alive) storedLastSsid = v }
        override var handledWifi: Long?
            get() = storedHandledWifi
            set(v) { if (alive) { storedHandledWifi = v; if (v != null) handledIds += v } }

        override fun snapshot() = DisconnectPolicy.Snapshot(
            wifiNetwork = wifi?.id,
            defaultIsCellular = defaultNet() == Net.CELL,
            ssid = if (settings.canReadSsid) supplicantSsid() else null,
        )

        override fun wifiConnected(network: Long) = wifi?.id == network

        override fun hotspotOn() = hotspot

        override suspend fun startHotspot(): String {
            if (!shizukuUp) error("Shizuku 未运行或未授权")
            if (alive) {
                hotspot = true
                appActions += Action(time, true)
            }
            delay(1_500)
            return "已通过 Shizuku 打开热点"
        }

        override suspend fun stopHotspot(): String {
            if (!shizukuUp) error("Shizuku: 未运行或未授权")
            if (alive) {
                hotspot = false
                appActions += Action(time, false)
            }
            delay(500)
            return "已通过 Shizuku 关闭热点"
        }

        override suspend fun waitForShizuku() {
            val deadline = time + 8_000
            while (!shizukuUp && time < deadline) delay(200)
        }

        override fun scheduleWaitForCellular() {
            if (!alive) return
            cellJobPending = true
            cellJobExpedited = settings.expeditedQuota
            pump()
        }

        override fun armWifiListener() {
            if (!alive) return
            listenerArmed = true
            listenerSent = false
            pump()
        }

        override fun scheduleRecheck(delayMs: Long) {
            if (!alive) return
            recheck?.cancel()
            recheck = system.launch {
                delay(delayMs)
                while (blockedByDoze(expedited = false)) delay(10_000)
                runHandler("recheck") { it.onCheck() }
            }
        }

        override fun now() = time
        override fun log(msg: String) { if (alive) logs += "${time / 1000}s $msg" }
        override fun progress(title: String, text: String) {}
        override fun done(title: String, text: String) {}
        override fun clearProgress() {}
        override fun failure(title: String, detail: String) { if (alive) failures += "$title: $detail" }
        override fun clearFailure() {}
    }
}
