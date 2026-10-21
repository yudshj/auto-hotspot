# 热点自动关 (Auto Hotspot Off)

Android 应用：连接到指定 Wi-Fi 时自动关闭手机热点；断开 Wi-Fi、改用移动数据时自动打开热点。

要求：Android 12+（在 Android 17 上测试），已安装并运行 [Shizuku](https://shizuku.rikka.app/)。

功能：
- 连接到列表中的 Wi-Fi 时关闭热点
- 断开 Wi-Fi、改用移动数据时打开热点（可选）

## 原理
- **检测 Wi-Fi 连上**：`ConnectivityManager.registerNetworkCallback(request, PendingIntent)`，系统用广播唤醒应用（无常驻服务、无常驻通知）。读取 SSID 需要精确位置 + 后台位置「始终允许」。
  注意系统每投递一次就会在约 5 秒后自动释放这条注册，所以要「接力」重新注册（见下）。
- **检测 Wi-Fi 断开**：JobScheduler 任务要求「蜂窝网络」，它按应用的默认网络判断，Wi-Fi 一断就能运行；任务里处理完再重新注册 Wi-Fi 监听。另有 15 分钟周期任务兜底。
  需要应用的「后台数据」未被限制（否则熄屏时蜂窝网络对本应用不可用，任务不会运行）。
- **关热点**：普通应用在 Android 16+ 上无法关闭热点（`TetheringManager.stopTethering` 会被静默忽略）。
  本应用通过 [Shizuku](https://shizuku.rikka.app/) 以 shell 身份调用 `ITetheringConnector.stopTethering`（shell 持有 `TETHER_PRIVILEGED`）。
- 不用前台服务的原因：Shizuku 只在应用 uid 启动/从缓存恢复时推送 binder；常驻前台服务会让 uid 一直活跃，Shizuku 晚于应用启动（如开机）时就永远收不到 binder。

## 构建 / 安装
```bash
# 需要 JDK 17 和 Android SDK（compileSdk 36）
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 可选：用 adb 直接授权（也可以在界面里点「授予」）
adb shell pm grant dev.autohotspot android.permission.ACCESS_FINE_LOCATION
adb shell pm grant dev.autohotspot android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant dev.autohotspot android.permission.POST_NOTIFICATIONS
```
然后在应用里授权 Shizuku、添加 Wi-Fi、打开总开关。

## 调试（仅 debug 构建）
```bash
D="adb shell am broadcast -n dev.autohotspot/.DebugReceiver -a"
$D state    # 打印热点/Shizuku 状态
$D sstart   # 通过 Shizuku 打开热点
$D stop     # 关闭热点
$D enable / disable
adb logcat -s AutoHotspot
```

## 注意
- 应用信息 → 移动数据和 WLAN →「后台数据」必须开启（界面里会检测并提示）。adb 可用 `adb shell cmd netpolicy remove restrict-background-blacklist <uid>`。
- 每次重启手机后 Shizuku 需要再次启动（无 root 时）。Shizuku 未运行导致关闭失败时，应用会发通知提醒。
- 热点开启时，系统在信号较弱的情况下可能不会自动回连 Wi-Fi，这时应用自然也不会被触发。
