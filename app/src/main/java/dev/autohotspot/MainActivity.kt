package dev.autohotspot

import android.Manifest
import android.content.Intent
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
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private var tick by mutableIntStateOf(0)
    private fun refresh() { tick++ }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }

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
        if (Prefs.isEnabled(this)) WifiMonitor.sync(this)
        setContent {
            AutoHotspotTheme {
                Screen(tick) // tick 变化触发重组，重新读取权限/偏好状态
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
        return WifiMonitor.currentSsid(this)
    }

    private val actions = MainActions(
        setEnabled = {
            Prefs.setEnabled(this, it)
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

    @Composable
    private fun Screen(@Suppress("UNUSED_PARAMETER") tick: Int) {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val state = MainUiState(
            enabled = Prefs.isEnabled(this),
            ssids = Prefs.ssids(this).sorted(),
            current = if (fine) currentSsid() else null,
            fineLocation = fine,
            backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
            writeSettings = HotspotController.canWriteSettings(this),
            shizukuRunning = HotspotController.shizukuRunning(),
            shizukuGranted = HotspotController.shizukuGranted(),
            hotspotOn = HotspotController.isHotspotEnabled(this),
            log = Prefs.log(this),
        )
        MainScreen(state, actions)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
