package dev.autohotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 热点状态通知：自动化触发开/关热点、以及确认热点已开/已关时发一条通知。
 * 四个节点共用同一个通知 ID 逐步更新（不会刷屏），渠道为低重要性（无声，只进通知栏），
 * 用来在后台确认自动化仍然存活、最近一次在什么时候做了什么。
 */
object Notifier {
    const val STATUS_CHANNEL_ID = "status"
    private const val STATUS_NOTIF_ID = 3

    fun ensureChannel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL_ID, "热点状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "自动打开/关闭热点时的进度与结果"
                setShowBadge(false)
            }
        )
    }

    /** 应用通知总开关和「热点状态」渠道都开着 */
    fun statusChannelEnabled(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        val ch = nm.getNotificationChannel(STATUS_CHANNEL_ID) ?: return true // 尚未创建时按默认（开）
        return ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** 已发出请求、等待系统完成（带不确定进度条） */
    fun progress(c: Context, title: String, text: String) = post(c, title, text, inProgress = true)

    /** 已确认完成 */
    fun done(c: Context, title: String, text: String) = post(c, title, text, inProgress = false)

    /** 操作失败时撤掉进度通知（失败另有高优先级提醒） */
    fun clear(c: Context) =
        c.getSystemService(NotificationManager::class.java).cancel(STATUS_NOTIF_ID)

    private fun post(c: Context, title: String, text: String, inProgress: Boolean) {
        val ctx = c.applicationContext
        if (!Prefs.isStatusNotify(ctx)) return
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hotspot_fg)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(inProgress)
            .setAutoCancel(!inProgress)
            .setCategory(Notification.CATEGORY_STATUS)
            .apply { if (inProgress) setProgress(0, 0, true) }
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(STATUS_NOTIF_ID, n) }
    }
}
