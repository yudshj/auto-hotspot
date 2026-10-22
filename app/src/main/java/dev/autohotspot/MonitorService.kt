package dev.autohotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * 实时监听：常驻前台服务，运行在独立的 `:monitor` 进程。
 *
 * 只做一件事：用 NetworkCallback 实时跟踪「当前的 Wi-Fi 连接」和「默认网络是不是移动数据」，
 * 一变就通知主进程检查一次（[CheckReceiver] → [AutoHotspotCore.onCheck]）。这样以下情况也能及时处理：
 * 离开没有互联网的 Wi-Fi（默认网络一直是移动数据，系统事件里没有它）、没有流量时直接换 Wi-Fi、
 * 不在电池白名单时 Doze 推迟后台任务。
 *
 * 开关热点留在主进程。本进程同时托管 ShizukuProvider，只负责接收 Shizuku 的 binder 并转给主进程
 * （见 [AutoHotspotApp]），自己不调用 Shizuku。
 * 也不读写 SharedPreferences（不支持多进程），日志只进 logcat。
 */
class MonitorService : Service() {
    private lateinit var cm: ConnectivityManager

    // 两个回调都在 ConnectivityManager 的同一个回调线程上执行
    private val wifiNetworks = mutableSetOf<Network>()
    private var defaultNetwork: Network? = null
    private var defaultIsCellular = false
    private var lastState: Pair<Set<Long>, Boolean>? = null

    private val wifiCallback = object : NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetworks += network
            changed()
        }

        override fun onLost(network: Network) {
            wifiNetworks -= network
            changed()
        }
    }

    private val defaultCallback = object : NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
        }

        // onAvailable 之后一定会紧跟一次；之后信号强度等变化也会频繁触发，只在「是不是移动数据」变了时才通知
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != defaultNetwork) return
            defaultIsCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            changed()
        }

        override fun onLost(network: Network) {
            if (network != defaultNetwork) return
            defaultNetwork = null
            defaultIsCellular = false
            changed()
        }
    }

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        // 注册时会立即收到当前状态，相当于启动时检查一次
        cm.registerNetworkCallback(WifiMonitor.wifiRequest(), wifiCallback)
        cm.registerDefaultNetworkCallback(defaultCallback)
        Log.i(TAG, "monitor started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        runCatching { cm.unregisterNetworkCallback(defaultCallback) }
        Log.i(TAG, "monitor stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun changed() {
        val state = wifiNetworks.map { it.networkHandle }.toSet() to defaultIsCellular
        if (state == lastState) return
        lastState = state
        Log.i(TAG, "network changed: wifi=${state.first} defaultIsCellular=${state.second}")
        // 广播送达、主进程拉起并拿到自己的唤醒锁之前，别让设备睡下去
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoHotspot:monitor")
            .acquire(10_000)
        sendBroadcast(Intent(this, CheckReceiver::class.java))
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "实时监听", NotificationManager.IMPORTANCE_MIN).apply {
                description = "实时监听 Wi-Fi 变化时常驻的通知，可以在这里关掉显示"
                setShowBadge(false)
            }
        )
        val pi = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hotspot_fg)
            .setContentTitle("正在实时监听 Wi-Fi")
            .setContentText("连上/断开 Wi-Fi 时立即开关热点")
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "AutoHotspot"
        private const val CHANNEL_ID = "monitor"
        private const val NOTIF_ID = 4
    }
}
