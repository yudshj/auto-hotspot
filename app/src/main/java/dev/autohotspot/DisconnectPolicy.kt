package dev.autohotspot

/**
 * 「Wi-Fi 断开 → 打开热点」相关的判断。只看传入的网络快照、不碰 Android API，便于单元测试。
 */
internal object DisconnectPolicy {
    /**
     * 网络快照。
     * @param wifiNetwork 当前 Wi-Fi 连接的 Network 句柄（真正连上才会有，每次连接都不同）；null = 没连
     * @param defaultIsCellular 默认网络是否走移动数据
     * @param ssid WifiManager.connectionInfo 读到的 SSID。系统自动重连、还在关联/握手时这里就已经有值，
     *   所以它不能用来判断「连着 Wi-Fi」，只用于日志
     */
    data class Snapshot(
        val wifiNetwork: Long?,
        val defaultIsCellular: Boolean,
        val ssid: String? = null,
    ) {
        val hasWifiNetwork get() = wifiNetwork != null
    }

    enum class Debounce { HANDLE, WIFI_BACK, NOT_CELLULAR }

    /** 断开后防抖结束时：是否按「已断开 Wi-Fi、改用移动数据」处理 */
    fun afterDebounce(s: Snapshot): Debounce = when {
        s.hasWifiNetwork -> Debounce.WIFI_BACK
        !s.defaultIsCellular -> Debounce.NOT_CELLULAR
        else -> Debounce.HANDLE
    }

    /** 周期/定时检查：有没处理的断开，且已经在用移动数据 → 补做断开处理 */
    fun missedDisconnect(s: Snapshot, pendingDisconnect: Boolean) =
        pendingDisconnect && !s.hasWifiNetwork && s.defaultIsCellular

    enum class Watch {
        /** 注册 Wi-Fi 监听（下次连上时投递；若已连着会立即投递） */
        ARM_WIFI_LISTENER,
        /** 同上，并等移动数据恢复后补做断开处理 */
        ARM_WIFI_LISTENER_AND_WAIT_FOR_CELLULAR,
        /** 安排「等移动数据」任务（Wi-Fi 断开、默认网络变成移动数据时运行） */
        WAIT_FOR_CELLULAR,
        /** 过一会儿再检查一次 */
        RECHECK,
    }

    /**
     * 处理完一次事件后，按当前网络安排下一次唤醒。
     * @param handledWifi 已按「连上」处理过的 Wi-Fi 连接
     * @param pendingDisconnect 有没处理完的断开（例如防抖结束时没有移动数据）
     */
    fun nextWatch(s: Snapshot, handledWifi: Long?, pendingDisconnect: Boolean): Watch = when {
        !s.hasWifiNetwork ->
            if (pendingDisconnect) Watch.ARM_WIFI_LISTENER_AND_WAIT_FOR_CELLULAR else Watch.ARM_WIFI_LISTENER
        // 新的连接还没处理：注册时系统会立即投递，按「连上」处理
        s.wifiNetwork != handledWifi -> Watch.ARM_WIFI_LISTENER
        // 连着但不是默认网络（多半没有互联网）：「等移动数据」任务会立即运行、空转，改成定时检查
        s.defaultIsCellular -> Watch.RECHECK
        else -> Watch.WAIT_FOR_CELLULAR
    }
}
