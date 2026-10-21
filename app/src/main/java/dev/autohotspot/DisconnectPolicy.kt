package dev.autohotspot

/**
 * 「Wi-Fi 断开 → 打开热点」的判断。只看传入的网络快照、不碰 Android API，便于单元测试。
 */
internal object DisconnectPolicy {
    /**
     * 网络快照。
     * @param hasWifiNetwork 系统里是否有 Wi-Fi 传输的 Network（真正连上才会有）
     * @param defaultIsCellular 默认网络是否走移动数据
     * @param ssid WifiManager.connectionInfo 读到的 SSID。系统自动重连、还在关联/握手时这里就已经有值，
     *   所以它不能用来判断「连着 Wi-Fi」，只用于日志
     */
    data class Snapshot(
        val hasWifiNetwork: Boolean,
        val defaultIsCellular: Boolean,
        val ssid: String? = null,
    )

    enum class Debounce { HANDLE, WIFI_BACK, NOT_CELLULAR }

    /** 断开后防抖结束时：是否按「已断开 Wi-Fi、改用移动数据」处理 */
    fun afterDebounce(s: Snapshot): Debounce = when {
        s.hasWifiNetwork -> Debounce.WIFI_BACK
        !s.defaultIsCellular -> Debounce.NOT_CELLULAR
        else -> Debounce.HANDLE
    }

    enum class Periodic { WAIT_FOR_CELLULAR, HANDLE_MISSED_DISCONNECT, ARM_WIFI_LISTENER }

    /**
     * 周期兜底该做什么。
     * @param pendingDisconnect 记录过连着的 Wi-Fi、但还没处理过它的断开（说明断开事件被漏掉了）
     */
    fun periodic(s: Snapshot, pendingDisconnect: Boolean): Periodic = when {
        s.hasWifiNetwork -> Periodic.WAIT_FOR_CELLULAR
        pendingDisconnect && s.defaultIsCellular -> Periodic.HANDLE_MISSED_DISCONNECT
        else -> Periodic.ARM_WIFI_LISTENER
    }
}
