package dev.autohotspot

import dev.autohotspot.DisconnectPolicy.Debounce
import dev.autohotspot.DisconnectPolicy.Snapshot
import dev.autohotspot.DisconnectPolicy.Watch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectPolicyTest {
    private val home = 101L
    private val onCellular = Snapshot(wifiNetwork = null, defaultIsCellular = true)
    private val onWifi = Snapshot(wifiNetwork = home, defaultIsCellular = false, ssid = "DeepSeek")
    private val noNetwork = Snapshot(wifiNetwork = null, defaultIsCellular = false)

    /**
     * 回归：AP 主动断开（DISASSOC_AP_BUSY）后系统立刻自动重连，防抖结束时还在握手。
     * 这时 connectionInfo 已经带着 SSID，但还没有 Wi-Fi Network，必须按断开处理。
     */
    @Test
    fun reconnectHandshakeInProgress_isTreatedAsDisconnected() {
        val handshaking = Snapshot(wifiNetwork = null, defaultIsCellular = true, ssid = "DeepSeek")
        assertEquals(Debounce.HANDLE, DisconnectPolicy.afterDebounce(handshaking))
    }

    @Test
    fun plainDisconnectToCellular_isHandled() {
        assertEquals(Debounce.HANDLE, DisconnectPolicy.afterDebounce(onCellular))
    }

    @Test
    fun wifiBackWithinDebounce_isIgnored() {
        assertEquals(Debounce.WIFI_BACK, DisconnectPolicy.afterDebounce(onWifi))
        // 移动数据仍是默认网络、但 Wi-Fi 网络已经回来（例如还在验证中）也不动作
        assertEquals(Debounce.WIFI_BACK, DisconnectPolicy.afterDebounce(onWifi.copy(defaultIsCellular = true)))
    }

    @Test
    fun noCellularDefault_isDeferred() {
        assertEquals(Debounce.NOT_CELLULAR, DisconnectPolicy.afterDebounce(noNetwork))
    }

    /** 回归：断开事件被漏掉后，检查时要补处理 */
    @Test
    fun missedDisconnect_onlyWhenPendingAndOnCellular() {
        assertTrue(DisconnectPolicy.missedDisconnect(onCellular, pendingDisconnect = true))
        assertTrue(DisconnectPolicy.missedDisconnect(onCellular.copy(ssid = "DeepSeek"), pendingDisconnect = true))
        // 已处理过（例如之后在流量下手动关了热点）：不能再次打开
        assertFalse(DisconnectPolicy.missedDisconnect(onCellular, pendingDisconnect = false))
        assertFalse(DisconnectPolicy.missedDisconnect(noNetwork, pendingDisconnect = true))
        assertFalse(DisconnectPolicy.missedDisconnect(onWifi, pendingDisconnect = true))
    }

    @Test
    fun nextWatch_noWifi_armsListener_andWaitsForCellularIfPending() {
        assertEquals(Watch.ARM_WIFI_LISTENER, DisconnectPolicy.nextWatch(onCellular, home, pendingDisconnect = false))
        assertEquals(
            Watch.ARM_WIFI_LISTENER_AND_WAIT_FOR_CELLULAR,
            DisconnectPolicy.nextWatch(noNetwork, home, pendingDisconnect = true),
        )
    }

    @Test
    fun nextWatch_unhandledWifi_armsListenerToGetItDelivered() {
        assertEquals(Watch.ARM_WIFI_LISTENER, DisconnectPolicy.nextWatch(onWifi, handledWifi = 99, pendingDisconnect = true))
    }

    @Test
    fun nextWatch_handledWifi_waitsForCellular() {
        assertEquals(Watch.WAIT_FOR_CELLULAR, DisconnectPolicy.nextWatch(onWifi, home, pendingDisconnect = true))
    }

    /** 回归：连着没有互联网的 Wi-Fi（默认网络仍是移动数据）时不能靠「等移动数据」任务，否则每 5 秒空转 */
    @Test
    fun nextWatch_handledWifiNotDefault_rechecksLater() {
        assertEquals(Watch.RECHECK, DisconnectPolicy.nextWatch(onWifi.copy(defaultIsCellular = true), home, pendingDisconnect = true))
    }
}
