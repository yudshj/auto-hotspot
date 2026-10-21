# 热点自动关 (Auto Hotspot Off)

连接到指定 Wi-Fi 时自动关闭手机热点。

## 原理
- **检测**：`ConnectivityManager.registerNetworkCallback(request, PendingIntent)`，Wi-Fi 连上时系统用广播唤醒应用（无常驻服务、无常驻通知）。读取 SSID 需要精确位置 + 后台位置「始终允许」。
- **关热点**：普通应用在 Android 16+ 上无法关闭热点（`TetheringManager.stopTethering` 会被静默忽略）。
  本应用通过 [Shizuku](https://shizuku.rikka.app/) 以 shell 身份调用 `ITetheringConnector.stopTethering`（shell 持有 `TETHER_PRIVILEGED`）。
- 不用前台服务的原因：Shizuku 只在应用 uid 启动/从缓存恢复时推送 binder；常驻前台服务会让 uid 一直活跃，Shizuku 晚于应用启动（如开机）时就永远收不到 binder。

## 构建 / 安装
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug
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
- 每次重启手机后 Shizuku 需要再次启动（无 root 时）。Shizuku 未运行导致关闭失败时，应用会发通知提醒。
- 热点开启时，系统在信号较弱的情况下可能不会自动回连 Wi-Fi，这时应用自然也不会被触发。
