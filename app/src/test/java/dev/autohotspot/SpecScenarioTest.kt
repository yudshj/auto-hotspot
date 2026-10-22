package dev.autohotspot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预期行为（与用户确认过）：
 * - 断开 Wi-Fi（不管是哪个）→ 打开热点；断开时没有移动数据就等流量恢复（期间没连回 Wi-Fi）再开。
 * - 连上 Wi-Fi →「任意 Wi-Fi」开：关闭热点；关：只有连上列表里的 Wi-Fi 才关闭。
 * - 只在连上/断开的那一刻动作，之后用户手动改了热点状态不改回。
 * - 「自动开关热点」总开关关掉就都不动作；5 秒内又连上不算断开；手动关 Wi-Fi 也算断开；
 *   读不到 SSID 时任意模式照样关、非任意模式不关。
 *
 * 「及时」= 事件后 15 秒内（防抖 5 秒 + 任务启动 + 等 Shizuku + 开/关热点）。
 * 这里的测试在「实时监听」关着时跑（只靠系统事件 + 定时检查的后备路径），
 * [SpecScenarioRealtimeTest] 在开着时把同样的测试再跑一遍，另外检查实时监听下已知限制都消失了。
 *
 * 实时监听关着时的已知限制（系统 API 做不到事件驱动，见 .agent/notes/auto-hotspot-expected-behavior.md）：
 * - 连着没有互联网的 Wi-Fi 时，断开靠每分钟一次的检查发现；
 * - 没有移动数据时从一个 Wi-Fi 直接换到另一个，新连接靠 15~30 分钟一次的周期任务发现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class SpecScenarioTest {
    protected open val realtime = false
    internal val anyWifi get() = SimSystem.Settings(anyWifi = true, realtime = realtime)
    internal val listOnly get() = SimSystem.Settings(anyWifi = false, ssids = setOf("Home", "Office"), realtime = realtime)

    internal fun scenario(settings: SimSystem.Settings, block: SimSystem.() -> Unit) = runTest {
        val sim = SimSystem(this, settings)
        sim.start()
        sim.block()
    }

    internal fun SimSystem.expectHotspot(expected: Boolean, why: String) =
        assertEquals("$why\n日志：\n${logs.joinToString("\n")}\n失败：$failures", expected, hotspot)

    protected val prompt = 15_000L
    protected val twoHours = 2 * 60 * 60_000L

    // ---------- 断开 → 开热点 ----------

    @Test
    fun anyWifiOn_walkAway_opensHotspot() = scenario(anyWifi) {
        connect("Home"); advance(30_000)
        loseWifi(); advance(prompt)
        expectHotspot(true, "离开 Wi-Fi、流量开着，应及时打开热点")
    }

    @Test
    fun listMode_leavingUnlistedWifi_alsoOpensHotspot() = scenario(listOnly) {
        connect("Cafe"); advance(30_000)
        loseWifi(); advance(prompt)
        expectHotspot(true, "断开任何 Wi-Fi 都应打开热点（与列表无关）")
    }

    @Test
    fun wifiTurnedOffByUser_countsAsDisconnect() = scenario(anyWifi) {
        connect("Home"); advance(30_000)
        setWifiEnabled(false); advance(prompt)
        expectHotspot(true, "手动关掉 Wi-Fi 也算断开")
    }

    /** 回归：AP 断开后系统立刻自动重连同一个网络，防抖结束时还在握手 */
    @Test
    fun disconnectDuringAutoReconnectHandshake_opensHotspot() = scenario(anyWifi) {
        connect("DeepSeek"); advance(30_000)
        loseWifi(); advance(2_000)
        startHandshake("DeepSeek"); advance(4_500)
        endHandshake(); advance(1_000)
        startHandshake("DeepSeek"); advance(prompt)
        expectHotspot(true, "握手中（还没有 Wi-Fi 网络）应按断开处理")
    }

    @Test
    fun lockedAndDozing_walkAway_opensHotspot() = scenario(anyWifi) {
        connect("Home"); advance(30_000)
        setDozing(true); advance(60 * 60_000)
        loseWifi(); advance(prompt)
        expectHotspot(true, "锁屏进入 Doze 后离开 Wi-Fi（应用在电池白名单），应及时打开热点")
    }

    @Test
    fun briefDisconnect_isIgnored() = scenario(anyWifi) {
        connect("Home"); advance(30_000)
        loseWifi(); advance(2_000)
        connect("Home"); advance(prompt)
        expectHotspot(false, "2 秒内连回不算断开")
        assertTrue("不应打开过热点：${appActions}", appActions.none { it.on })
    }

    // ---------- 断开时没有流量 ----------

    @Test
    fun dataOffAtDisconnect_opensOnceDataReturns() = scenario(anyWifi) {
        setData(false)
        connect("Home"); advance(30_000)
        loseWifi(); advance(10 * 60_000)
        expectHotspot(false, "没有流量时先不开")
        setData(true); advance(prompt)
        expectHotspot(true, "流量恢复且仍未连 Wi-Fi，应及时打开")
    }

    @Test
    fun noSignalAtDisconnect_opensOnceSignalReturns() = scenario(anyWifi) {
        connect("Home"); advance(30_000)
        setCellSignal(false); advance(1_000)
        loseWifi(); advance(3 * 60_000)
        expectHotspot(false, "没信号时先不开")
        setCellSignal(true); advance(prompt)
        expectHotspot(true, "信号恢复且仍未连 Wi-Fi，应及时打开")
    }

    @Test
    fun cellularFlapsDuringDebounce_opensOnceStable() = scenario(anyWifi) {
        connect("Home"); advance(30_000)
        loseWifi(); advance(3_000)
        setCellSignal(false); advance(4_000) // 防抖结束那一刻没信号
        setCellSignal(true); advance(prompt)
        expectHotspot(true, "防抖期间信号闪断，信号恢复后应及时打开")
    }

    @Test
    fun dataOffAtDisconnect_reconnectFirst_neverOpens() = scenario(anyWifi) {
        setData(false)
        connect("Home"); advance(30_000)
        loseWifi(); advance(5 * 60_000)
        connect("Home"); advance(60_000)
        setData(true); advance(twoHours)
        expectHotspot(false, "流量恢复前已连回 Wi-Fi，不应再开")
    }

    @Test
    fun manualToggleWhileWaitingForData_stillOpensWhenDataReturns() = scenario(anyWifi) {
        setData(false)
        connect("Home"); advance(30_000)
        loseWifi(); advance(5 * 60_000)
        userHotspot(true); advance(60_000)
        userHotspot(false); advance(60_000)
        setData(true); advance(prompt)
        expectHotspot(true, "等流量期间手动开关过热点，流量恢复时照样打开")
    }

    @Test
    fun rebootWhileWaitingForData_isFreshStart() = scenario(anyWifi) {
        setData(false)
        connect("Home"); advance(30_000)
        loseWifi(); advance(60_000)
        reboot(); advance(60_000)
        setData(true); advance(twoHours)
        expectHotspot(false, "开机算重新开始，关机前没处理完的断开不再补做")
    }

    // ---------- 连上 → 关热点 ----------

    @Test
    fun anyWifiOn_connectAnyWifi_closesHotspot() = scenario(anyWifi) {
        userHotspot(true); advance(30_000)
        connect("Cafe"); advance(prompt)
        expectHotspot(false, "任意模式：连上任何 Wi-Fi 都关")
    }

    @Test
    fun listMode_connectUnlisted_keepsHotspot() = scenario(listOnly) {
        userHotspot(true); advance(30_000)
        connect("Cafe"); advance(twoHours)
        expectHotspot(true, "列表模式：连上不在列表里的 Wi-Fi 不关")
    }

    @Test
    fun listMode_connectListed_closesHotspot() = scenario(listOnly) {
        userHotspot(true); advance(30_000)
        connect("Office"); advance(prompt)
        expectHotspot(false, "列表模式：连上列表里的 Wi-Fi 要关")
    }

    @Test
    fun listMode_unlistedThenListed_closesOnListed() = scenario(listOnly) {
        connect("Cafe"); advance(30_000)
        userHotspot(true); advance(30_000)
        loseWifi(); advance(60_000)
        connect("Home"); advance(prompt)
        expectHotspot(false, "从不在列表的 Wi-Fi 离开、连上列表里的 Wi-Fi，应关")
    }

    /** 没有流量时从一个 Wi-Fi 直接换到另一个（中间没有移动数据可用）。已知限制：靠周期任务发现 */
    @Test
    fun listMode_switchWifiWithoutCellular_closesWithinPeriodicWindow() = scenario(listOnly) {
        setData(false)
        connect("Cafe"); advance(30_000)
        userHotspot(true); advance(30_000)
        loseWifi(); advance(60_000)
        connect("Home"); advance(30 * 60_000 + prompt)
        expectHotspot(false, "流量关着时换到列表里的 Wi-Fi，最晚在下一次周期检查时关")
    }

    @Test
    fun unreadableSsid_anyWifiCloses_listModeDoesNot() {
        scenario(anyWifi.copy(canReadSsid = false)) {
            userHotspot(true); advance(30_000)
            connect("Home"); advance(prompt)
            expectHotspot(false, "任意模式读不到 SSID 照样关")
        }
        scenario(listOnly.copy(canReadSsid = false)) {
            userHotspot(true); advance(30_000)
            connect("Home"); advance(twoHours)
            expectHotspot(true, "列表模式读不到 SSID 不关")
        }
    }

    /** 连上没有互联网的 Wi-Fi（未验证，默认网络仍是移动数据） */
    @Test
    fun unvalidatedWifi_closesHotspot_withoutBusyLoop() = scenario(anyWifi) {
        userHotspot(true); advance(30_000)
        connect("Router-NoSIM", validated = false); advance(prompt)
        expectHotspot(false, "连上未验证的 Wi-Fi 也算连上")
        val before = handlerRuns.size
        advance(60 * 60_000)
        val runs = handlerRuns.drop(before)
        val busy = runs.filter { it.kind != "periodic" && it.kind != "recheck" }
        assertTrue("稳定连着 Wi-Fi 一小时，不应被反复唤醒（${busy.size} 次）：${logs.takeLast(6)}", busy.isEmpty())
        assertTrue("定时检查应约每分钟一次", runs.count { it.kind == "recheck" } in 55..61)
    }

    /** 已知限制：连着没有互联网的 Wi-Fi 时，断开靠每分钟一次的检查发现 */
    @Test
    fun unvalidatedWifi_walkAway_opensWithinAMinute() = scenario(anyWifi) {
        connect("Router-NoSIM", validated = false); advance(10 * 60_000)
        loseWifi(); advance(60_000 + prompt)
        expectHotspot(true, "离开没有互联网的 Wi-Fi，最晚约 1 分钟后打开")
    }

    @Test
    fun wifiValidatesLater_walkAway_opensPromptly() = scenario(anyWifi) {
        connect("Slow", validated = false); advance(20_000)
        validateWifi(); advance(5 * 60_000)
        loseWifi(); advance(prompt)
        expectHotspot(true, "Wi-Fi 验证通过、成为默认网络后，断开应及时发现")
    }

    // ---------- 只在那一刻动作，手动操作不改回 ----------

    @Test
    fun manualOffAfterAutoOn_isNotReverted() = scenario(anyWifi) {
        connect("Home"); advance(30_000)
        loseWifi(); advance(prompt)
        expectHotspot(true, "先自动打开")
        userHotspot(false); advance(twoHours)
        expectHotspot(false, "流量下手动关掉后不应再打开")
    }

    @Test
    fun manualOnWhileOnWifi_isNotReverted() = scenario(listOnly) {
        connect("Home"); advance(30_000)
        userHotspot(true); advance(twoHours)
        expectHotspot(true, "连着 Wi-Fi 时手动打开热点，不应被关掉")
        openApp(); advance(prompt)
        expectHotspot(true, "打开应用界面也不应关掉手动打开的热点")
    }

    @Test
    fun manualOnWhileOnUnvalidatedWifi_isNotReverted() = scenario(anyWifi) {
        connect("Router-NoSIM", validated = false); advance(30_000)
        userHotspot(true); advance(10 * 60_000)
        expectHotspot(true, "连着未验证的 Wi-Fi 时手动打开热点，不应被关掉")
    }

    // ---------- 总开关 ----------

    @Test
    fun masterSwitchOff_neitherOpensNorCloses() = scenario(anyWifi.copy(enabled = false)) {
        connect("Home"); advance(30_000)
        loseWifi(); advance(twoHours)
        expectHotspot(false, "「自动开关热点」关着：断开不开")
        userHotspot(true); advance(30_000)
        connect("Home"); advance(twoHours)
        expectHotspot(true, "「自动开关热点」关着：连上不关")
        assertTrue("不应有任何动作：$appActions", appActions.isEmpty())
    }

    // ---------- 进程被杀 ----------

    /** 进程在处理到一半时被杀（这里卡在等 Shizuku）：处理完才记「已处理」，之后的检查会补做 */
    @Test
    fun processKilledMidDisconnect_isRedoneLater() = scenario(anyWifi) {
        connect("Home"); advance(30_000)
        setShizuku(false)
        loseWifi(); advance(9_000)
        killProcess()
        setShizuku(true); advance(31 * 60_000)
        expectHotspot(true, "处理断开时进程被杀，最晚下一次周期检查补做")
    }

    @Test
    fun processKilledMidConnect_isRedoneLater() = scenario(anyWifi) {
        userHotspot(true); advance(30_000)
        setShizuku(false)
        connect("Home"); advance(3_000)
        killProcess()
        setShizuku(true); advance(31 * 60_000)
        expectHotspot(false, "处理连上时进程被杀，最晚下一次周期检查补做")
    }

    // ---------- 重启 ----------

    @Test
    fun rebootOnWifi_thenWalkAway_opensHotspot() = scenario(anyWifi) {
        connect("Home"); advance(30_000)
        reboot(); advance(5_000)
        connect("Home"); advance(60_000)
        loseWifi(); advance(prompt)
        expectHotspot(true, "重启后离开 Wi-Fi，应及时打开热点")
    }
}
