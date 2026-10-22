package dev.autohotspot

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自动开/关热点的流程：收到「Wi-Fi 连上」「默认网络变成移动数据」「周期兜底」后决定做什么。
 * 不直接调用 Android API，系统能力都由 [Env] 提供：Android 实现在 [WifiMonitor]，
 * 单元测试里换成模拟的系统（虚拟时间），所以这里的等待都用挂起函数。
 *
 * 预期行为：
 * - 断开 Wi-Fi（不管是哪个）且默认网络变成移动数据 → 打开热点；断开时没有移动数据就等它恢复（期间没连回 Wi-Fi）再开。
 * - 连上 Wi-Fi → 「任意 Wi-Fi」模式下关闭热点，否则只有连上列表里的 Wi-Fi 才关。
 * - 只在连上/断开的那一刻动作，之后用户手动改了热点状态不会被改回去。
 * - 以上都由「自动开关热点」一个总开关控制。
 *   （重新注册监听时系统会把当前连接再投递一次，靠 [Env.handledWifi] 认出来，不当成新的「连上」。）
 *
 * 事件来源：PendingIntent 网络回调（[onWifiAvailable]）、「等移动数据」任务（[onCellularDefault]）、
 * 周期兜底 / 定时检查 / 实时监听（[onCheck]）。来源之间有重叠，靠 [Env.handledWifi] / [Env.lastSsid] 去重。
 *
 * 详见 .agent/notes/auto-hotspot-expected-behavior.md。
 */
internal class AutoHotspotCore(private val env: Env) {
    interface Env {
        /** 「自动开关热点」总开关 */
        val enabled: Boolean
        val anyWifi: Boolean
        val ssids: Set<String>

        /** 最近连上、还没处理过断开的 Wi-Fi（「?」= 读不到名称），持久保存 */
        var lastSsid: String?

        /** 已按「连上」处理过的 Wi-Fi 连接（Network 句柄），持久保存；开机时清掉（句柄会重新从头分配） */
        var handledWifi: Long?

        fun snapshot(): DisconnectPolicy.Snapshot

        /** 这次 Wi-Fi 连接（Network 句柄）还在不在 */
        fun wifiConnected(network: Long): Boolean
        fun hotspotOn(): Boolean

        /** 打开/关闭热点并确认结果，返回描述；失败抛异常 */
        suspend fun startHotspot(): String
        suspend fun stopHotspot(): String

        /** 刚被拉起时 Shizuku 的 binder 是异步送达的，等一会儿 */
        suspend fun waitForShizuku()

        /** 安排一个 Wi-Fi 断开、默认网络变成移动数据时才会运行的任务（同 ID 替换旧任务） */
        fun scheduleWaitForCellular()

        /** 注册「Wi-Fi 可用」监听；若此刻已连着 Wi-Fi，系统会立即投递一次 */
        fun armWifiListener()

        /** 过 [delayMs] 后调用一次 [onCheck]（同 ID 替换旧任务） */
        fun scheduleRecheck(delayMs: Long)

        fun now(): Long
        fun log(msg: String)

        /** 状态通知：进行中 / 完成 / 撤掉 */
        fun progress(title: String, text: String)
        fun done(title: String, text: String)
        fun clearProgress()

        /** 失败提醒 / 撤掉失败提醒 */
        fun failure(title: String, detail: String)
        fun clearFailure()
    }

    // 各种入口（Wi-Fi 回调、「等移动数据」任务、周期/定时检查、实时监听）可能同时进来：串行处理，
    // 后进来的会发现事件已处理过（handledWifi / lastSsid 已更新）
    private val lock = Mutex()

    val isActive get() = env.enabled

    private fun secs(t0: Long) = "%.1f".format((env.now() - t0) / 1000.0)

    /**
     * 开机时调用（在重新注册监听之前）：开机算重新开始。
     * Network 句柄会重新分配，记下的「已处理的连接」作废；关机前没处理完的断开也不再补做
     * （否则在家重启、移动数据比 Wi-Fi 先连上时会先开热点再关掉）。
     */
    fun onBoot() {
        env.handledWifi = null
        env.lastSsid = null
    }

    /**
     * Wi-Fi 连上时调用（PendingIntent 网络回调）。[network] 是这次连接的 Network 句柄；
     * 重新注册监听（打开界面、开关切换、检查）时系统会把当前连接再投递一次，那不是新的「连上」，只补上该有的监听。
     */
    suspend fun onWifiAvailable(ssid: String?, network: Long?) {
        lock.withLock {
            if (network != null && network == env.handledWifi) {
                keepWatching()
                return
            }
            handleConnect(ssid, network)
        }
    }

    /**
     * 默认网络变成移动网络时调用（「等移动数据」任务，Wi-Fi 刚断开）：开着「自动打开」就打开热点。
     * 只响应「Wi-Fi → 移动数据」这个切换事件，所以在流量下手动关掉热点后不会被反复打开。
     */
    suspend fun onCellularDefault() {
        try {
            lock.withLock { handleDisconnect() }
        } finally {
            keepWatching()
        }
    }

    /**
     * 按当前网络补做没处理的事件，再安排下一次唤醒。由周期兜底、定时检查和实时监听（网络一变就调）调用：
     * - 有还没处理过的 Wi-Fi 连接（例如没有流量时直接换了一个 Wi-Fi）→ 按「连上」处理；
     * - 有没处理的断开且已在用移动数据（例如断开时系统正在自动重连）→ 按「断开」处理。
     * @param realtime 来自实时监听：这是正常路径，不记「补做」日志
     */
    suspend fun onCheck(realtime: Boolean = false) {
        if (!isActive) return
        try {
            lock.withLock {
                val s = env.snapshot()
                val last = env.lastSsid
                when {
                    s.wifiNetwork != null && s.wifiNetwork != env.handledWifi -> {
                        if (!realtime) env.log("检查：发现未处理的 Wi-Fi 连接")
                        handleConnect(s.ssid, s.wifiNetwork)
                    }
                    DisconnectPolicy.missedDisconnect(s, pendingDisconnect = last != null) -> {
                        if (!realtime) env.log("检查：发现未处理的 Wi-Fi 断开（${if (last == "?") "Wi-Fi" else last}）")
                        handleDisconnect()
                    }
                }
            }
        } finally {
            keepWatching()
        }
    }

    /** 处理完一次事件后，按当前网络安排下一次唤醒（实时监听之外的后备） */
    private fun keepWatching(s: DisconnectPolicy.Snapshot = env.snapshot()) {
        if (!isActive) return
        when (DisconnectPolicy.nextWatch(s, env.handledWifi, pendingDisconnect = env.lastSsid != null)) {
            // 若 Wi-Fi 在这期间又连上了，注册时系统会立即投递，流程照常继续
            DisconnectPolicy.Watch.ARM_WIFI_LISTENER -> env.armWifiListener()
            DisconnectPolicy.Watch.ARM_WIFI_LISTENER_AND_WAIT_FOR_CELLULAR -> {
                env.armWifiListener()
                env.scheduleWaitForCellular()
            }
            DisconnectPolicy.Watch.WAIT_FOR_CELLULAR -> env.scheduleWaitForCellular()
            DisconnectPolicy.Watch.RECHECK -> env.scheduleRecheck(RECHECK_MS)
        }
    }

    /**
     * 连上 Wi-Fi：连着列表中的 Wi-Fi（或开了「任意 Wi-Fi」）且热点开着，就关掉热点。须持有 [lock]。
     * 做完才记为已处理：进程在中途被杀的话，下次检查会发现这个连接还没处理、补做一次。
     */
    private suspend fun handleConnect(ssid: String?, network: Long?) {
        try {
            if (network != null && !env.wifiConnected(network)) {
                // 排队等锁（例如前一个断开还在防抖）期间这次连接已经断开了：只记下来，随后的断开照常处理；
                // 不再关热点，否则会「开 → 关 → 开」来回闪
                if (ssid != null && env.lastSsid != ssid) env.log("已连接 Wi-Fi：$ssid（随即断开）")
                env.lastSsid = ssid ?: "?"
                keepWatching() // 让它的断开尽快被处理
                return
            }
            connect(ssid)
        } finally {
            env.handledWifi = network
        }
    }

    private suspend fun connect(ssid: String?) {
        // 监听已被系统释放，靠「等移动数据」任务在 Wi-Fi 断开后重新注册。
        // 刚连上时可能还没验证完、默认网络仍是移动数据：任务会立即运行一次，防抖后改成定时检查
        env.scheduleWaitForCellular()

        if (ssid != null && env.lastSsid != ssid) env.log("已连接 Wi-Fi：$ssid")
        env.lastSsid = ssid ?: "?"

        if (!env.enabled) return
        // 「任意 Wi-Fi」模式不看 SSID（读不到名称时也照样关闭）
        if (!env.anyWifi && (ssid == null || ssid !in env.ssids)) return
        if (!env.hotspotOn()) return
        val name = ssid ?: "Wi-Fi"
        env.progress("正在关闭热点…", "已连接「$name」")
        env.waitForShizuku()
        val t0 = env.now()
        try {
            val msg = env.stopHotspot()
            env.log("[连接 $name] → $msg")
            env.done("热点已关闭", "已连接「$name」· 用时 ${secs(t0)} 秒")
            env.clearFailure()
        } catch (t: Throwable) {
            env.clearProgress()
            env.log("[连接 $name] → 关闭失败：${t.message}")
            env.failure("已连接 $name，但无法自动关闭热点", t.message ?: t.toString())
        }
    }

    /** 断开 Wi-Fi：防抖后仍没连回、且在用移动数据，就打开热点。须持有 [lock] */
    private suspend fun handleDisconnect() {
        val last = env.lastSsid?.let { if (it == "?") "Wi-Fi" else it } ?: return
        // 防抖：Wi-Fi 漫游/短暂掉线时不动作
        delay(DEBOUNCE_MS)
        val s = env.snapshot()
        when (DisconnectPolicy.afterDebounce(s)) {
            DisconnectPolicy.Debounce.WIFI_BACK -> {
                if (s.wifiNetwork != env.handledWifi) {
                    env.log("Wi-Fi 短暂断开后已恢复（$last），不动作")
                } else if (s.defaultIsCellular) {
                    env.log("Wi-Fi「$last」连着，但默认网络是移动数据（可能没有互联网），改为每分钟检查一次")
                }
                return
            }
            DisconnectPolicy.Debounce.NOT_CELLULAR -> {
                // 保留 last：移动数据一恢复，「等移动数据」任务 / 实时监听就会再来
                env.log("已断开 Wi-Fi：$last，但默认网络不是移动数据，稍后再检查")
                return
            }
            DisconnectPolicy.Debounce.HANDLE -> {}
        }
        val retrying = s.ssid?.let { "（系统仍在尝试连接 $it）" } ?: ""
        env.log("已断开 Wi-Fi：$last，改用移动数据$retrying")
        try {
            if (!env.enabled || env.hotspotOn()) return
            env.progress("正在打开热点…", "已断开「$last」，改用移动数据")
            env.waitForShizuku()
            val t0 = env.now()
            try {
                val msg = env.startHotspot()
                env.log("[改用移动数据] → $msg")
                env.done("热点已开启", "已断开「$last」，改用移动数据 · 用时 ${secs(t0)} 秒")
                env.clearFailure()
            } catch (t: Throwable) {
                env.clearProgress()
                env.log("[改用移动数据] → 打开热点失败：${t.message}")
                env.failure("已断开 $last，但无法自动打开热点", t.message ?: t.toString())
            }
        } finally {
            // 做完才清掉：进程在中途被杀的话，下次检查会补做
            env.lastSsid = null
        }
    }

    companion object {
        const val DEBOUNCE_MS = 5000L
        /** 连着不是默认网络的 Wi-Fi 时，多久检查一次它断开没有（实时监听关着或没在运行时的后备） */
        const val RECHECK_MS = 60_000L
    }
}
