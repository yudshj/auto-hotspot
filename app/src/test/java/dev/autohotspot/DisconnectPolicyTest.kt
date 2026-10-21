package dev.autohotspot

import dev.autohotspot.DisconnectPolicy.Debounce
import dev.autohotspot.DisconnectPolicy.Periodic
import dev.autohotspot.DisconnectPolicy.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class DisconnectPolicyTest {
    private val onCellular = Snapshot(hasWifiNetwork = false, defaultIsCellular = true)
    private val onWifi = Snapshot(hasWifiNetwork = true, defaultIsCellular = false, ssid = "DeepSeek")
    private val noNetwork = Snapshot(hasWifiNetwork = false, defaultIsCellular = false)

    /**
     * 回归：AP 主动断开（DISASSOC_AP_BUSY）后系统立刻自动重连，防抖结束时还在握手。
     * 这时 connectionInfo 已经带着 SSID，但还没有 Wi-Fi Network，必须按断开处理。
     */
    @Test
    fun reconnectHandshakeInProgress_isTreatedAsDisconnected() {
        val handshaking = Snapshot(hasWifiNetwork = false, defaultIsCellular = true, ssid = "DeepSeek")
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

    /** 回归：断开事件被漏掉后，周期兜底要补处理，而不是只重新注册 Wi-Fi 监听 */
    @Test
    fun periodic_missedDisconnectOnCellular_isHandled() {
        assertEquals(Periodic.HANDLE_MISSED_DISCONNECT, DisconnectPolicy.periodic(onCellular, pendingDisconnect = true))
        val handshaking = onCellular.copy(ssid = "DeepSeek")
        assertEquals(Periodic.HANDLE_MISSED_DISCONNECT, DisconnectPolicy.periodic(handshaking, pendingDisconnect = true))
    }

    /** 断开已处理过（例如之后在流量下手动关了热点），周期兜底不能再次打开 */
    @Test
    fun periodic_disconnectAlreadyHandled_onlyArmsListener() {
        assertEquals(Periodic.ARM_WIFI_LISTENER, DisconnectPolicy.periodic(onCellular, pendingDisconnect = false))
    }

    @Test
    fun periodic_pendingButNoCellular_waits() {
        assertEquals(Periodic.ARM_WIFI_LISTENER, DisconnectPolicy.periodic(noNetwork, pendingDisconnect = true))
    }

    @Test
    fun periodic_onWifi_waitsForCellular() {
        assertEquals(Periodic.WAIT_FOR_CELLULAR, DisconnectPolicy.periodic(onWifi, pendingDisconnect = true))
        assertEquals(Periodic.WAIT_FOR_CELLULAR, DisconnectPolicy.periodic(onWifi, pendingDisconnect = false))
    }
}
