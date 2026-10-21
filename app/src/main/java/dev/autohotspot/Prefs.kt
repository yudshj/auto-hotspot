package dev.autohotspot

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private const val NAME = "auto_hotspot"
    const val KEY_ENABLED = "enabled"
    const val KEY_SSIDS = "ssids"
    const val KEY_AUTO_ON = "auto_on"
    const val KEY_LOG = "log"
    private const val MAX_LOG = 100

    fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = sp(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_ENABLED, v).apply()

    /** 断开 Wi-Fi、改用移动数据时自动打开热点 */
    fun isAutoOn(c: Context) = sp(c).getBoolean(KEY_AUTO_ON, false)
    fun setAutoOn(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_AUTO_ON, v).apply()

    fun ssids(c: Context): Set<String> = sp(c).getStringSet(KEY_SSIDS, emptySet())!!.toSet()

    fun addSsid(c: Context, ssid: String) {
        val s = ssid.trim()
        if (s.isEmpty()) return
        sp(c).edit().putStringSet(KEY_SSIDS, ssids(c) + s).apply()
    }

    fun removeSsid(c: Context, ssid: String) =
        sp(c).edit().putStringSet(KEY_SSIDS, ssids(c) - ssid).apply()

    fun log(c: Context): List<String> =
        sp(c).getString(KEY_LOG, "")!!.split('\n').filter { it.isNotEmpty() }

    @Synchronized
    fun appendLog(c: Context, msg: String) {
        android.util.Log.i("AutoHotspot", msg)
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$ts  ${msg.replace('\n', ' ')}"
        val lines = (listOf(line) + log(c)).take(MAX_LOG)
        sp(c).edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    fun clearLog(c: Context) = sp(c).edit().remove(KEY_LOG).apply()
}

/** WifiInfo.ssid 返回带引号的字符串，或 <unknown ssid> */
fun normalizeSsid(raw: String?): String? {
    if (raw == null) return null
    if (raw == "<unknown ssid>" || raw.isBlank()) return null
    return if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
        raw.substring(1, raw.length - 1)
    } else raw
}
