package dev.autohotspot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「实时监听」开着：[SpecScenarioTest] 的所有场景再跑一遍，另外检查关着时的已知限制在这里都能及时处理。
 */
class SpecScenarioRealtimeTest : SpecScenarioTest() {
    override val realtime = true

    @Test
    fun unvalidatedWifi_walkAway_opensPromptly() = scenario(anyWifi) {
        connect("Router-NoSIM", validated = false); advance(10 * 60_000)
        loseWifi(); advance(prompt)
        expectHotspot(true, "离开没有互联网的 Wi-Fi，实时监听下应及时打开")
    }

    @Test
    fun listMode_switchWifiWithoutCellular_closesPromptly() = scenario(listOnly) {
        setData(false)
        connect("Cafe"); advance(30_000)
        userHotspot(true); advance(30_000)
        loseWifi(); advance(60_000)
        connect("Home"); advance(prompt)
        expectHotspot(false, "流量关着时换到列表里的 Wi-Fi，实时监听下应及时关")
    }

    @Test
    fun notBatteryExempt_dozing_walkAway_opensPromptly() =
        scenario(anyWifi.copy(batteryExempt = false, expeditedQuota = false)) {
            connect("Router-NoSIM", validated = false); advance(30_000)
            setDozing(true); advance(2 * 60 * 60_000)
            loseWifi(); advance(prompt)
            expectHotspot(true, "不在电池白名单、Doze 中，实时监听下仍应及时打开")
        }

    /** 断开的防抖期间又短暂连上一个 Wi-Fi（不到 1 秒）：轮到处理时它已经断了，不应「开 → 关 → 开」来回闪 */
    @Test
    fun briefConnectDuringDebounce_noFlapping() = scenario(anyWifi) {
        setData(false)
        connect("Home"); advance(30_000)
        loseWifi(); advance(60_000)
        setData(true); advance(1_000)
        connect("Office"); advance(700)
        loseWifi(); advance(prompt)
        expectHotspot(true, "最终应打开")
        assertEquals("只应动作一次：$appActions", 1, appActions.size)
    }

    /** 用户在「活动应用」里停止了整个应用：退回后备路径；周期任务（在电池白名单里）会把实时监听拉起来 */
    @Test
    fun userStopsApp_monitorComesBackWithPeriodicJob() = scenario(listOnly) {
        connect("Cafe"); advance(30_000)
        stopApp(); advance(31 * 60_000)
        setData(false)
        userHotspot(true); advance(30_000)
        loseWifi(); advance(60_000)
        connect("Home"); advance(prompt)
        expectHotspot(false, "实时监听被周期任务拉起后，应及时处理")
    }

    @Test
    fun userStopsApp_openingAppRestartsMonitor() = scenario(anyWifi.copy(batteryExempt = false)) {
        connect("Router-NoSIM", validated = false); advance(30_000)
        stopApp(); advance(60_000)
        openApp(); advance(30_000)
        loseWifi(); advance(prompt)
        expectHotspot(true, "打开界面后实时监听恢复")
    }
}
