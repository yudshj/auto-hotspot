package dev.autohotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kotlin.concurrent.thread

/** 仅 debug：通过 adb 广播测试热点控制逻辑 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            val out = try {
                when (intent.action) {
                    "start" -> startTethering(context)
                    "sstart" -> "startViaShizuku=${HotspotController.startViaShizuku()}"
                    "stop" -> HotspotController.stopHotspot(context)
                    "tmstop" -> HotspotController.stopViaTetheringManager(context)
                    "enable" -> { Prefs.setEnabled(context, true); WifiMonitor.sync(context); "enabled" }
                    "disable" -> { Prefs.setEnabled(context, false); WifiMonitor.sync(context); "disabled" }
                    else -> ""
                } + " | hotspot=${HotspotController.isHotspotEnabled(context)}" +
                    " shizukuRunning=${HotspotController.shizukuRunning()}" +
                    " shizukuGranted=${HotspotController.shizukuGranted()}" +
                    " writeSettings=${HotspotController.canWriteSettings(context)}"
            } catch (t: Throwable) {
                Log.e(TAG, "debug failed", t); "ERROR ${t}"
            }
            Log.i(TAG, "debug ${intent.action}: $out")
            pending.finish()
        }
    }

    private fun startTethering(c: Context): String {
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
        val tm = c.getSystemService("tethering")!!
        val cbCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val cb = Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, a ->
            Log.i(TAG, "StartTetheringCallback.${m.name}(${a?.joinToString()})"); null
        }
        val m = tm.javaClass.getMethod(
            "startTethering", Int::class.javaPrimitiveType, Executor::class.java, cbCls
        )
        m.invoke(tm, 0, Executor { it.run() }, cb)
        return "startTethering sent"
    }

    companion object { const val TAG = "AutoHotspot" }
}
