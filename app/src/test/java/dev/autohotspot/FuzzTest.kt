package dev.autohotspot

import dev.autohotspot.SimSystem.Net
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * 模糊测试：随机生成「走远/走近、自动重连握手、Wi-Fi 无互联网、流量开关、信号丢失、锁屏 Doze、手动开关热点、
 * 打开界面、主进程被回收、用户停止应用、重启」的事件序列（「实时监听」开关随机），用 [SimSystem] 驱动真实流程，用 [Oracle]（按预期行为推出的参考模型）对照检查。
 *
 * 事件之间要么是「抖动」（≤2.5 秒，连续抖动合计 < 4 秒，短于防抖时间），要么是「稳定」（≥90 秒）。
 * 参考模型只看稳定状态之间的变化，所以抖动期间的瞬时状态不算连上/断开。
 *
 * 检查项：
 * - 稳定期结束时热点状态符合预期（实时监听在运行时必须及时；没在运行时允许按已知限制晚一些，见 [Oracle.expect]）；
 * - 应用的每次开/关动作都不违背当时的预期（例如不能关掉用户手动打开的热点）；
 * - 稳定期内不空转（不反复被唤醒）。
 *
 * 另外统计「按已知限制晚做」的动作：事件发生时实时监听在运行的，一次都不允许。
 *
 * 复现某个种子：把 [seeds] 换成 listOf(种子)。深度跑：把 [seeds] 调到 1..30000（约 20 秒）。
 */
class FuzzTest {
    private val seeds = 1..5000
    private val stepsPerRun = 60
    private var lateTotal = 0
    private var lateWithMonitor = 0
    private var lateSeeds = mutableListOf<Int>()

    @Test
    fun fuzz() {
        val failures = mutableListOf<String>()
        for (seed in seeds) {
            val r = runSeed(seed, stepsPerRun)
            if (r != null) {
                // 找最短的失败前缀，方便看
                val shortest = (1..stepsPerRun).firstNotNullOf { n -> runSeed(seed, n)?.let { n to it } }
                failures += "种子 $seed（前 ${shortest.first} 步即失败）\n${shortest.second}"
                if (failures.size >= 3) break
            }
        }
        println("按已知限制补做的动作：$lateTotal 次（其中实时监听在运行时 $lateWithMonitor 次），种子 ${lateSeeds.take(20)}")
        if (lateWithMonitor > 0) failures += "实时监听在运行时发生的事件被晚处理了 $lateWithMonitor 次（应为 0）"
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n========\n\n"))
    }

    private fun randomSettings(rnd: Random) = SimSystem.Settings(
        enabled = rnd.nextDouble() < 0.9,
        anyWifi = rnd.nextBoolean(),
        ssids = setOf("Home", "Office"),
        canReadSsid = rnd.nextDouble() < 0.85,
        batteryExempt = rnd.nextDouble() < 0.7,
        expeditedQuota = rnd.nextDouble() < 0.8,
        realtime = rnd.nextDouble() < 0.6,
    )

    /** 跑一个种子的前 [steps] 步；失败返回说明，通过返回 null */
    private fun runSeed(seed: Int, steps: Int): String? {
        var result: String? = null
        runTest {
            val rnd = Random(seed)
            val settings = randomSettings(rnd)
            val sim = SimSystem(this, settings, Random(seed * 31 + 7))
            val oracle = Oracle(settings)
            val trace = mutableListOf<String>()
            fun t() = "%7.1fs".format(sim.time / 1000.0)

            sim.start()
            sim.advance(1_000)
            oracle.stable(sim, segment = Segment.NETWORK)

            var burst = 0L
            // 上一个稳定期检查到哪儿了（抖动期间应用的动作也计入下一个稳定期）
            var actionsMark = 0
            var runsMark = 0
            var storm: String? = null
            var stormLeft = 0
            var step = 0
            while (step < steps && result == null) {
                val inBurst = burst > 0
                // ---- 选一个事件 ----
                val options = buildList {
                    val w = sim.wifi
                    if (w != null) {
                        add(3.0 to "lose")
                        add(2.0 to "lose+retry")
                        add(0.7 to "wifiOff")
                        if (!w.validated) add(2.0 to "validate")
                    } else if (sim.wifiEnabled) {
                        add(4.0 to "connect")
                    } else {
                        add(2.0 to "wifiOn")
                    }
                    add(1.2 to "data")
                    add(0.8 to "signal")
                    if (!inBurst) {
                        add(0.8 to "doze")
                        add(1.5 to "hotspot")
                        add(0.8 to "openApp")
                        add(0.15 to "reboot")
                        add(0.4 to "killProcess")
                        if (sim.monitorRunning) add(0.3 to "stopApp")
                    }
                }
                var pick = rnd.nextDouble() * options.sumOf { it.first }
                val action = options.first { pick -= it.first; pick <= 0 }.second
                var segment = Segment.NETWORK
                val desc = when (action) {
                    "lose" -> { sim.loseWifi(); "离开 Wi-Fi" }
                    "lose+retry" -> {
                        val ssid = sim.wifi!!.ssid
                        sim.loseWifi(); storm = ssid; stormLeft = rnd.nextInt(2, 12)
                        "离开 Wi-Fi，系统反复尝试重连 $ssid"
                    }
                    "wifiOff" -> { sim.setWifiEnabled(false); "手动关闭 Wi-Fi" }
                    "wifiOn" -> { sim.setWifiEnabled(true); "手动打开 Wi-Fi" }
                    "validate" -> { sim.validateWifi(); "Wi-Fi 验证通过（有互联网了）" }
                    "connect" -> {
                        val ssid = listOf("Home", "Office", "Cafe", "Mall").random(rnd)
                        val validated = rnd.nextDouble() < 0.8
                        storm = null; stormLeft = 0
                        sim.connect(ssid, validated)
                        "连上 $ssid" + if (validated) "" else "（没有互联网）"
                    }
                    "data" -> { sim.setData(!sim.dataEnabled); "移动数据 ${onOff(sim.dataEnabled)}" }
                    "signal" -> { sim.setCellSignal(!sim.cellSignal); "移动信号 ${if (sim.cellSignal) "恢复" else "丢失"}" }
                    "doze" -> {
                        segment = Segment.OTHER
                        sim.setDozing(!sim.dozing); if (sim.dozing) "锁屏并进入 Doze" else "解锁（退出 Doze）"
                    }
                    "hotspot" -> {
                        segment = if (sim.hotspot) Segment.MANUAL_OFF else Segment.MANUAL_ON
                        sim.userHotspot(!sim.hotspot); "用户手动${if (sim.hotspot) "打开" else "关闭"}热点"
                    }
                    "openApp" -> { segment = Segment.OTHER; sim.openApp(); "打开应用界面" }
                    "killProcess" -> { segment = Segment.OTHER; sim.killProcess(); "系统回收主进程" }
                    "stopApp" -> { segment = Segment.OTHER; sim.stopApp(); "用户在「活动应用」里停止应用" }
                    "reboot" -> { segment = Segment.REBOOT; storm = null; stormLeft = 0; sim.reboot(); "重启" }
                    else -> error(action)
                }
                trace += "${t()}  $desc"
                step++

                // ---- 停留：抖动（合计 < 4 秒）或稳定 ----
                val goBurst = segment == Segment.NETWORK && rnd.nextDouble() < 0.3 && burst < 1_500
                val dwell = if (goBurst) {
                    rnd.nextLong(200, minOf(2_500, 3_900 - burst))
                } else {
                    logUniform(rnd, 90_000.0, 45 * 60_000.0).toLong()
                }
                if (goBurst) {
                    burst += dwell
                    sim.advance(dwell)
                    continue
                }
                burst = 0
                oracle.stable(sim, segment)
                val segStart = sim.time
                // 停留期间：系统可能在反复尝试重连（握手闪现，不产生 Wi-Fi 网络）
                var left = dwell
                while (left > 0) {
                    val chunk = minOf(left, if (stormLeft > 0) rnd.nextLong(1_500, 6_000) else left)
                    if (stormLeft > 0 && sim.wifi == null) {
                        if (sim.handshaking == null) sim.startHandshake(storm!!) else { sim.endHandshake(); stormLeft-- }
                    }
                    sim.advance(chunk)
                    left -= chunk
                }
                if (sim.handshaking != null) sim.endHandshake()
                stormLeft = 0

                val problem = oracle.check(sim, segStart, sim.appActions.drop(actionsMark), sim.handlerRuns.drop(runsMark))
                actionsMark = sim.appActions.size
                runsMark = sim.handlerRuns.size
                if (step == steps && steps == stepsPerRun && oracle.lateActions > 0) {
                    lateTotal += oracle.lateActions; lateSeeds += seed
                    lateWithMonitor += oracle.lateWithMonitor
                }
                if (problem != null) {
                    result = buildString {
                        appendLine("问题：$problem")
                        appendLine("设置：$settings")
                        appendLine("事件：")
                        trace.forEach { appendLine("  $it") }
                        appendLine("应用动作：${sim.appActions.map { "${it.time / 1000}s ${if (it.on) "开" else "关"}" }}")
                        appendLine("Wi-Fi 连接：${sim.sessions.takeLast(8)}")
                        appendLine("最近的唤醒：${sim.handlerRuns.takeLast(12).map { "${it.time / 1000}s ${it.kind}" }}")
                        appendLine("应用日志：")
                        sim.logs.forEach { appendLine("  $it") }
                        if (sim.failures.isNotEmpty()) appendLine("失败提醒：${sim.failures}")
                    }
                }
            }
        }
        return result
    }

    private fun onOff(b: Boolean) = if (b) "打开" else "关闭"
    private fun logUniform(rnd: Random, lo: Double, hi: Double) = exp(ln(lo) + rnd.nextDouble() * (ln(hi) - ln(lo)))

    internal enum class Segment { NETWORK, MANUAL_ON, MANUAL_OFF, REBOOT, OTHER }

    /**
     * 参考模型：按预期行为（.agent/notes/auto-hotspot-expected-behavior.md）推出热点此刻应该开还是关。
     * 连上/断开取自模拟器记录的每次 Wi-Fi 连接（再短的连接也算连上），两次连接间隔不到 5 秒不算断开。
     */
    internal class Oracle(private val s: SimSystem.Settings) {
        enum class Exp { ON, OFF, ANY }

        private var exp = Exp.ANY
        private var due = 0L
        private var why = "启动"
        private var late = false
        /** 当前预期来自一次「连上时监听不在」的连接：应用只有在它断开前发现它才需要动作 */
        private var missedSession: SimSystem.Session? = null
        /** 本稳定期内依次出现过的预期（抖动里连上又断开时，中间的动作也合理） */
        private val segmentExps = mutableSetOf<Exp>()
        /** 按已知限制晚做的动作（允许，但单独统计） */
        var lateActions = 0; private set
        /** 其中事件发生时实时监听在运行的（应为 0：实时监听在时不该有晚动作） */
        var lateWithMonitor = 0; private set
        private var openFromDisconnect = false
        /** 断开后该开、应用还没开的热点（再连上 Wi-Fi 前，晚开都算合理；期间手动改了热点也照样会开） */
        private var unfulfilledOpen = false
        private var openSetWithMonitor = false

        private var seen = 0
        private var ended = 0
        private var disconnectAt: Long? = null
        private var disconnectSlow = false
        private var pendingOpen = false
        private var pendingSlow = false

        private fun qualifies(ssid: String) = s.enabled && (s.anyWifi || (s.canReadSsid && ssid in s.ssids))

        /**
         * 允许的延迟：实时监听在运行时一律 15 秒。没在运行时按已知限制：正常 15 秒；离开没有互联网的 Wi-Fi 靠每分钟一次的检查，80 秒；
         * 连上时应用的监听不在（例如没有流量时从一个 Wi-Fi 换到另一个），靠周期任务发现，31 分钟；
         * 不在电池白名单且在 Doze 中，普通任务只在维护窗口运行，61 分钟；
         * 不在白名单时，靠普通任务（定时检查/周期任务）的情况之后还可能遇上 Doze，再加 61 分钟。
         */
        private fun expect(e: Exp, sim: SimSystem, reason: String, slow: Boolean = false, missed: Boolean = false) {
            val dozeBlocked = sim.dozing && !s.batteryExempt
            val isLate = !sim.monitorRunning && (dozeBlocked || missed || slow)
            exp = e
            openFromDisconnect = false
            late = isLate
            missedSession = null
            val dozeLater = if ((missed || slow) && !s.batteryExempt) 61 * 60_000L else 0L
            due = sim.time + when {
                sim.monitorRunning -> 15_000L
                dozeBlocked -> 61 * 60_000L + 15_000
                missed -> 31 * 60_000L + 15_000 + dozeLater
                slow -> 80_000L + dozeLater
                else -> 15_000L
            }
            why = reason
            segmentExps += e
        }

        /** 世界进入一个新的稳定状态（之后至少稳定 90 秒） */
        fun stable(sim: SimSystem, segment: Segment) {
            segmentExps.clear()
            when (segment) {
                Segment.REBOOT -> {
                    exp = Exp.ANY; why = "重启"; late = false; unfulfilledOpen = false
                    seen = sim.sessions.size; ended = seen
                    disconnectAt = null; pendingOpen = false
                    return
                }
                // 等流量期间（或应用被推迟、还没开）手动改了热点：之后照样打开（pendingOpen 保留）
                Segment.MANUAL_ON -> {
                    expect(Exp.ON, sim, "用户手动打开"); late = false
                    if (unfulfilledOpen) pendingOpen = true
                    return
                }
                Segment.MANUAL_OFF -> {
                    expect(Exp.OFF, sim, "用户手动关闭"); late = false
                    if (unfulfilledOpen) pendingOpen = true
                    return
                }
                Segment.OTHER, Segment.NETWORK -> {}
            }
            // 按时间顺序处理新的连上/断开
            val events = buildList {
                sim.sessions.forEachIndexed { i, x ->
                    if (i >= seen) add(x.start to { connected(sim, x) })
                    if (i >= ended && x.end != null) add(x.end!! to { disconnected(x) })
                }
            }.sortedBy { it.first }
            events.forEach { it.second() }
            seen = sim.sessions.size
            ended = sim.sessions.indexOfFirst { it.end == null }.let { if (it == -1) sim.sessions.size else it }

            if (sim.wifi == null && disconnectAt != null) {
                // 稳定期内一直没连回：真正的断开
                if (s.enabled) { pendingOpen = true; pendingSlow = disconnectSlow }
                disconnectAt = null
            }
            val cell = sim.defaultNet() == Net.CELL
            if (pendingOpen && sim.wifi == null && cell) {
                pendingOpen = false
                expect(Exp.ON, sim, "断开 Wi-Fi、在用移动数据", slow = pendingSlow)
                openFromDisconnect = true
                unfulfilledOpen = true
                openSetWithMonitor = sim.monitorRunning
            } else if (openFromDisconnect && exp == Exp.ON && !sim.hotspot && sim.wifi != null) {
                // 还没来得及开（允许的延迟内）就又连上了 Wi-Fi：不用再开
                exp = Exp.ANY; why = "晚开之前已连回 Wi-Fi"; openFromDisconnect = false
            } else if (openFromDisconnect && exp == Exp.ON && !sim.hotspot && sim.wifi == null && !cell) {
                // 还没来得及开（允许的延迟内），移动数据又没了：回到「等流量」
                exp = Exp.ANY; why = "等流量"; openFromDisconnect = false; pendingOpen = true
            }
        }

        private fun connected(sim: SimSystem, x: SimSystem.Session) {
            disconnectAt = null // 不到 5 秒就连回（或第一次连上）：不算断开
            pendingOpen = false
            unfulfilledOpen = false
            // 抖动里连上又断开的短暂连接：应用可能没看到，或轮到处理时已经断了而不动作（避免来回闪），也可能关了再开——都行
            if (x.end != null) {
                exp = Exp.ANY; why = "短暂连接 ${x.ssid}"; missedSession = null
                if (qualifies(x.ssid)) segmentExps += Exp.OFF
                return
            }
            if (qualifies(x.ssid)) {
                expect(Exp.OFF, sim, "连上 ${x.ssid}", missed = !x.deliverable)
                if (!x.deliverable) missedSession = x
            }
        }

        private fun disconnected(x: SimSystem.Session) {
            disconnectAt = x.end
            disconnectSlow = !x.wasDefault
        }

        /** 一个稳定期结束：检查期间应用的动作和结束时的热点状态 */
        fun check(sim: SimSystem, segStart: Long, actions: List<SimSystem.Action>, runs: List<SimSystem.Run>): String? {
            for (a in actions) {
                val v = if (a.on) Exp.ON else Exp.OFF
                when {
                    exp == Exp.ANY || exp == v || v in segmentExps -> { if (a.on) unfulfilledOpen = false }
                    lateAllowed(sim, a) -> {
                        // 已知限制导致的补做；发生在稳定点之后才成为最新状态（之前的会被抖动里更新的事件取代）
                        lateActions++
                        // 晚关只会发生在连上时监听不在（deliverable = false，实时监听当时没在运行）；晚开看当初断开时
                        if (a.on && openSetWithMonitor) lateWithMonitor++
                        if (a.on) { unfulfilledOpen = false; pendingOpen = false }
                        if (a.time >= segStart) { exp = v; due = a.time; why = "按已知限制补做" }
                    }
                    else -> return "${a.time / 1000}s 应用${if (a.on) "打开" else "关闭"}了热点，但此时预期是 $exp（$why）"
                }
            }
            if (actions.size > 2) return "一个稳定期内应用开关了 ${actions.size} 次热点"
            val busy = runs.filter { it.time > segStart + 30_000 && (it.kind == "wifi" || it.kind == "cellular") }
            if (busy.size > 3) return "稳定期内被反复唤醒 ${busy.size} 次（${busy.take(5).map { "${it.time / 1000}s ${it.kind}" }}）"
            val dwell = sim.time - segStart
            val rechecks = runs.count { it.kind == "recheck" && it.time >= segStart }
            if (rechecks > dwell / 55_000 + 2) return "稳定期 ${dwell / 1000}s 内定时检查 $rechecks 次"
            missedSession?.let {
                if (it.end != null && it.id !in sim.handledIds) { exp = Exp.ANY; why = "应用没机会看到的连接"; missedSession = null }
            }
            // 应用已经处理完那次断开（热点本来就开着时它不用动作）：之后不会再补开
            if (!sim.appPendingDisconnect) { unfulfilledOpen = false; pendingOpen = false }
            if (exp != Exp.ANY && sim.hotspot != (exp == Exp.ON) && sim.time >= due) {
                return "稳定期结束时热点${if (sim.hotspot) "开" else "关"}着，预期 $exp（$why，最晚 ${due / 1000}s）"
            }
            return null
        }

        /**
         * 按已知限制晚做、但当初是该做的动作：
         * - 关：这次连接上来时监听不在（应用晚发现它），而它确实该关；
         * - 开：断开后该开、应用还没开的热点，且之后没再连上 Wi-Fi。
         */
        private fun lateAllowed(sim: SimSystem, a: SimSystem.Action): Boolean {
            if (a.on) return unfulfilledOpen
            val x = sim.sessions.lastOrNull { it.start <= a.time } ?: return false
            val connectedThen = x.end == null || x.end!! >= a.time
            return connectedThen && !x.deliverable && qualifies(x.ssid)
        }
    }
}
