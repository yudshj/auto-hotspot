package dev.autohotspot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.autohotspot.ui.AutoHotspotTheme
import dev.autohotspot.ui.MainActions
import dev.autohotspot.ui.MainScreen
import dev.autohotspot.ui.MainUiState
import dev.autohotspot.ui.NetworkKind
import dev.autohotspot.ui.HotspotState
import android.telephony.TelephonyManager
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private var tick by mutableIntStateOf(0)
    private fun refresh() { tick++ }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }

    /**
     * 界面可见时实时跟踪 Wi-Fi 连接（含 SSID），网络或热点状态一变就刷新。
     * 断开时 WifiManager.connectionInfo 可能还短暂残留旧 SSID，所以以回调为准。
     */
    private val liveWifi = ConcurrentHashMap<Network, String>()
    @Volatile private var liveTracking = false

    private val wifiCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ssid = normalizeSsid((caps.transportInfo as? WifiInfo)?.ssid) ?: ""
            if (liveWifi.put(network, ssid) != ssid) runOnUiThread { refresh() }
        }

        override fun onLost(network: Network) {
            liveWifi.remove(network)
            runOnUiThread { refresh() }
        }
    }

    /** 默认网络（实际承载流量的那个）类型，状态卡片据此显示 Wi-Fi 还是移动数据 */
    @Volatile private var defaultKind: NetworkKind? = null

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
                else -> NetworkKind.Other
            }
            if (defaultKind != kind) { defaultKind = kind; runOnUiThread { refresh() } }
        }

        override fun onLost(network: Network) {
            defaultKind = NetworkKind.None
            runOnUiThread { refresh() }
        }
    }

    private fun networkKind(): NetworkKind {
        defaultKind?.let { return it }
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return NetworkKind.None) ?: return NetworkKind.None
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
            else -> NetworkKind.Other
        }
    }

    private fun carrierName(): String? = runCatching {
        getSystemService(TelephonyManager::class.java).networkOperatorName.takeIf { it.isNotBlank() }
    }.getOrNull()

    private val apReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.d("AutoHotspot", "UI got AP state broadcast: ${intent.getIntExtra(HotspotController.EXTRA_WIFI_AP_STATE, -1)}")
            refresh()
        }
    }

    /** 热点处于「启动中/关闭中」时每 300ms 重读一次，直到稳定（系统启动热点约需 3 秒） */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val transitionPoll = Runnable { refresh() }
    private fun pollWhileTransitioning(h: HotspotState) {
        handler.removeCallbacks(transitionPoll)
        if (liveTracking && (h == HotspotState.Starting || h == HotspotState.Stopping)) {
            handler.postDelayed(transitionPoll, 300)
        }
    }

    override fun onStart() {
        super.onStart()
        liveWifi.clear()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifiCallback
        )
        liveTracking = true
        defaultKind = null
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(defaultCallback)
        registerReceiver(
            apReceiver, IntentFilter(HotspotController.ACTION_WIFI_AP_STATE_CHANGED), RECEIVER_EXPORTED
        )
    }

    override fun onStop() {
        liveTracking = false
        handler.removeCallbacks(transitionPoll)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(defaultCallback) }
        runCatching { unregisterReceiver(apReceiver) }
        super.onStop()
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        // 打开界面时重新注册一次监听（幂等），防止注册因某些原因丢失
        if (Prefs.isEnabled(this) || Prefs.isAutoOn(this)) WifiMonitor.sync(this)
        setContent {
            AutoHotspotTheme {
                // 在这里直接读取 tick（快照状态），tick 一变本作用域就重组、重新读取所有状态。
                // 注意不能写成 Screen(tick) 而函数体不用它：Compose 编译器会忽略未使用的参数，导致永不重组。
                val t = tick
                MainScreen(buildState(t), actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun currentSsid(): String? {
        if (liveTracking) {
            // 回调说没有 Wi-Fi 就是没连；有 Wi-Fi 但 SSID 读不到（权限）时退回 WifiManager
            if (liveWifi.isEmpty()) return null
            liveWifi.values.firstOrNull { it.isNotEmpty() }?.let { return it }
        }
        return WifiMonitor.currentSsid(this)
    }

    private val actions = MainActions(
        setEnabled = {
            Prefs.setEnabled(this, it)
            WifiMonitor.sync(this)
        },
        setAutoOn = {
            Prefs.setAutoOn(this, it)
            WifiMonitor.sync(this)
        },
        addSsid = { Prefs.addSsid(this, it) },
        removeSsid = { Prefs.removeSsid(this, it) },
        requestLocation = {
            permLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        },
        requestBackgroundLocation = {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) toast("请先授予精确位置")
            else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        },
        openAppSettings = {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        },
        requestBatteryUnrestricted = {
            // 直接弹出系统对话框；部分系统不支持时退回电池优化列表
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        },
        requestNotifications = {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        },
        requestShizuku = {
            if (HotspotController.shizukuRunning()) Shizuku.requestPermission(1)
            else toast("请先打开 Shizuku 应用并启动服务")
        },
        requestWriteSettings = {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        },
        testStopHotspot = {
            thread {
                val msg = try {
                    if (!HotspotController.isHotspotEnabled(this)) "热点当前未开启（仍尝试关闭）\n" else ""
                } catch (_: Throwable) { "" } + try {
                    HotspotController.stopHotspot(this)
                } catch (t: Throwable) { "失败：${t.message}" }
                Prefs.appendLog(this, "[手动测试] $msg")
                runOnUiThread { toast(msg) }
            }
        },
        clearLog = { Prefs.clearLog(this) },
    )

    @Suppress("UNUSED_PARAMETER")
    private fun buildState(tick: Int): MainUiState {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val state = MainUiState(
            enabled = Prefs.isEnabled(this),
            autoOn = Prefs.isAutoOn(this),
            ssids = Prefs.ssids(this).sorted(),
            current = if (fine) currentSsid() else null,
            network = networkKind(),
            carrier = carrierName(),
            fineLocation = fine,
            backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryUnrestricted = getSystemService(android.os.PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName),
            backgroundData = getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus !=
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            writeSettings = HotspotController.canWriteSettings(this),
            shizukuRunning = HotspotController.shizukuRunning(),
            shizukuGranted = HotspotController.shizukuGranted(),
            hotspot = when (HotspotController.apState(this)) {
                HotspotController.WIFI_AP_STATE_ENABLING -> HotspotState.Starting
                HotspotController.WIFI_AP_STATE_ENABLED -> HotspotState.On
                HotspotController.WIFI_AP_STATE_DISABLING -> HotspotState.Stopping
                -1 -> if (HotspotController.isHotspotEnabled(this)) HotspotState.On else HotspotState.Off
                else -> HotspotState.Off
            },
            log = Prefs.log(this),
        )
        pollWhileTransitioning(state.hotspot)
        return state
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
