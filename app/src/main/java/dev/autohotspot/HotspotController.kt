package dev.autohotspot

import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 关闭 Wi-Fi 热点。普通应用没有公开 API 可以关热点，这里有两条路径：
 *
 * 1. Shizuku：以 shell(uid 2000) 身份调用 tethering 服务的 ITetheringConnector.stopTethering，
 *    shell 拥有 TETHER_PRIVILEGED，最可靠。
 * 2. 直接调用 TetheringManager.stopTethering（@SystemApi，反射调用）。
 *    TetheringService 对非特权调用者的要求是：已授予“修改系统设置”(WRITE_SETTINGS)，
 *    且运营商不要求热点授权检查(provisioning)。
 */
object HotspotController {
    private const val TETHERING_WIFI = 0
    private const val WIFI_AP_STATE_ENABLING = 12
    private const val WIFI_AP_STATE_ENABLED = 13
    const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    const val EXTRA_WIFI_AP_STATE = "wifi_state"

    @Volatile
    private var bypassed = false

    private fun ensureHiddenApiAccess() {
        if (bypassed) return
        try {
            HiddenApiBypass.addHiddenApiExemptions("")
        } catch (t: Throwable) {
            android.util.Log.w("AutoHotspot", "HiddenApiBypass failed", t)
        }
        bypassed = true
    }

    fun isApStateOn(state: Int) = state == WIFI_AP_STATE_ENABLED || state == WIFI_AP_STATE_ENABLING

    /** 读取热点状态：粘性广播 WIFI_AP_STATE_CHANGED，失败时回退到 WifiManager.isWifiApEnabled */
    fun isHotspotEnabled(c: Context): Boolean {
        try {
            val i = c.applicationContext.registerReceiver(
                null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED
            )
            if (i != null) return isApStateOn(i.getIntExtra(EXTRA_WIFI_AP_STATE, -1))
        } catch (_: Throwable) {
        }
        return try {
            ensureHiddenApiAccess()
            val wm = c.applicationContext.getSystemService(WifiManager::class.java)
            wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as Boolean
        } catch (_: Throwable) {
            false
        }
    }

    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun shizukuGranted(): Boolean = try {
        shizukuRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun canWriteSettings(c: Context) = Settings.System.canWrite(c)

    /**
     * 关闭热点，返回描述性结果；失败时抛出异常。
     * 每种方式调用后都会轮询确认热点真的关了（新版 Android 上非特权调用会被静默忽略）。
     */
    fun stopHotspot(c: Context): String {
        ensureHiddenApiAccess()
        val wasOn = isHotspotEnabled(c)
        val errors = mutableListOf<String>()
        if (shizukuGranted()) {
            try {
                val msg = stopViaShizuku()
                if (!wasOn || waitUntilOff(c)) return msg
                errors += "Shizuku: 调用成功但热点仍开启"
            } catch (t: Throwable) {
                errors += "Shizuku: ${t.root()}"
            }
        } else {
            errors += "Shizuku: 未运行或未授权"
        }
        if (canWriteSettings(c)) {
            try {
                val msg = stopViaTetheringManager(c)
                if (!wasOn || waitUntilOff(c)) return msg
                errors += "TetheringManager: 请求被系统忽略，热点仍开启"
            } catch (t: Throwable) {
                errors += "TetheringManager: ${t.root()}"
            }
        } else {
            errors += "TetheringManager: 未授予“修改系统设置”"
        }
        throw IllegalStateException(errors.joinToString("; "))
    }

    private fun waitUntilOff(c: Context, timeoutMs: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isHotspotEnabled(c)) return true
            Thread.sleep(200)
        }
        return !isHotspotEnabled(c)
    }

    private fun Throwable.root(): String {
        var t: Throwable = this
        while (t.cause != null && t.cause !== t) t = t.cause!!
        return "${t.javaClass.simpleName}: ${t.message}"
    }

    private fun stopViaShizuku(): String {
        val result = callConnector("stopTethering") { it.parameterTypes[0] == Int::class.javaPrimitiveType }
            ?.let { arg0 -> arg0(TETHERING_WIFI) }
            ?: return "已通过 Shizuku 发送关闭请求（未收到回执）"
        if (result != 0) error("stopTethering 返回错误码 $result")
        return "已通过 Shizuku 关闭热点"
    }

    /** 仅用于调试：通过 Shizuku 打开热点 */
    fun startViaShizuku(): Int? {
        ensureHiddenApiAccess()
        val reqCls = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val builder = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
        val req = builder.javaClass.getMethod("build").invoke(builder)
        val parcel = reqCls.getMethod("getParcel").invoke(req)
        return callConnector("startTethering") {
            it.parameterTypes[0].name == "android.net.TetheringRequestParcel"
        }?.let { it(parcel) }
    }

    /**
     * 以 shell 身份调用 ITetheringConnector 的 method(arg0, callerPkg, attributionTag, IIntResultListener)，
     * 返回一个“传入第一个参数 → 等待结果码（超时为 null）”的函数。
     */
    private fun callConnector(
        name: String,
        match: (java.lang.reflect.Method) -> Boolean,
    ): ((Any?) -> Int?)? {
        val raw = SystemServiceHelper.getSystemService("tethering")
            ?: error("找不到 tethering 服务")
        val binder = ShizukuBinderWrapper(raw)
        val stub = Class.forName("android.net.ITetheringConnector\$Stub")
        val connector = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        val listenerCls = Class.forName("android.net.IIntResultListener")
        val method = connector.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == 4 && match(it)
        } ?: error("ITetheringConnector.$name 不存在: " +
            connector.javaClass.methods.filter { it.name.contains("Tether") }.joinToString { it.toString() })

        return { arg0 ->
            val latch = CountDownLatch(1)
            var result = Int.MIN_VALUE
            val resultBinder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == FIRST_CALL_TRANSACTION) {
                        data.enforceInterface("android.net.IIntResultListener")
                        result = data.readInt()
                        latch.countDown()
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }
            val listener = Proxy.newProxyInstance(listenerCls.classLoader, arrayOf(listenerCls)) { _, m, args ->
                when (m.name) {
                    "asBinder" -> resultBinder
                    "hashCode" -> resultBinder.hashCode()
                    "equals" -> args?.get(0) === resultBinder
                    "toString" -> "IIntResultListenerProxy"
                    else -> null
                }
            }
            // 以 shell 身份调用，callerPkg 必须和调用 uid 对应
            method.invoke(connector, arg0, "com.android.shell", null, listener)
            if (latch.await(5, TimeUnit.SECONDS)) result else null
        }
    }

    internal fun stopViaTetheringManager(c: Context): String {
        val tm = c.applicationContext.getSystemService("tethering") ?: error("TetheringManager 不可用")
        tm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI)
        return "已通过 TetheringManager 关闭热点 (SDK ${Build.VERSION.SDK_INT})"
    }
}
