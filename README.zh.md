# 热点自动关 (Auto Hotspot Off)

[ [English](README.md) | **中文** ]

[![AI-DECLARATION: auto](https://img.shields.io/badge/䷼%20AI--DECLARATION-auto-ede9fe?labelColor=ede9fe)](https://ai-declaration.md)
[![breakdown: copilot](https://img.shields.io/badge/breakdown-copilot-fee2e2?labelColor=fee2e2)](AI-DECLARATION.md)
[![design: copilot](https://img.shields.io/badge/design-copilot-fee2e2?labelColor=fee2e2)](AI-DECLARATION.md)
[![testing: copilot](https://img.shields.io/badge/testing-copilot-fee2e2?labelColor=fee2e2)](AI-DECLARATION.md)
[![implementation: auto](https://img.shields.io/badge/implementation-auto-ede9fe?labelColor=ede9fe)](AI-DECLARATION.md)
[![review: auto](https://img.shields.io/badge/review-auto-ede9fe?labelColor=ede9fe)](AI-DECLARATION.md)

Android 应用：**断开 Wi-Fi、改用移动数据时自动打开手机热点**，**连上 Wi-Fi（任意 Wi-Fi，或只限列表中的）时自动关闭**。

要求：Android 12+（在 Android 17 上测试），已安装并运行 [Shizuku](https://shizuku.rikka.app/)。

## 功能

- **Wi-Fi 列表**：一键添加当前 Wi-Fi、手动输入 SSID、删除。
- **自动开关热点**（一个总开关，默认开启）：断开 Wi-Fi、改用移动数据时打开热点；连上 Wi-Fi 时关闭。「任意 Wi-Fi」开关默认开启，此时连上任何 Wi-Fi 都关闭热点；关掉它就只对列表中的网络生效。断开时流量关着的话，等流量恢复再打开。只在连上/断开的那一刻动作，之后手动开关热点不会被改回去。
- **实时监听**（默认开启）：独立进程里的前台服务实时监听网络变化，各种情况都能在几秒内处理；会常驻一条静默、最低优先级的通知。关掉后靠系统事件和定时检查，少数情况可能晚几分钟。
- **状态卡片**：当前网络（Wi-Fi 名称 / 移动数据 + 运营商）、热点状态（含「启动中… / 关闭中…」），实时刷新。
- **状态通知**（无声，可关闭）：自动化触发时显示「正在打开/关闭热点…」，确认后变为「热点已开启/已关闭」，附原因和用时，可据此确认后台自动化仍在工作。
- 各项必需权限与设置的**检测和提示**、操作失败时的通知（如 Shizuku 未运行）、应用内日志。
- 无前台服务、无常驻通知；重启手机和更新应用后自动恢复。

## 原理

- **开关热点**：普通应用在 Android 16+ 上无法开关热点（`TetheringManager.stopTethering` 会被静默忽略）。本应用通过 Shizuku 以 shell 身份调用 `ITetheringConnector.start/stopTethering`（shell 持有 `TETHER_PRIVILEGED`）。
- **检测「Wi-Fi 连上」**：`ConnectivityManager.registerNetworkCallback(request, PendingIntent)`，系统用广播唤醒应用。读取 SSID 需要精确位置 + 后台位置「始终允许」。
  系统每投递一次就会在约 5 秒后自动释放这条注册，所以要「接力」重新注册（见下）。
- **检测「Wi-Fi 断开、改用移动数据」**：JobScheduler 任务要求「蜂窝网络」。任务的网络约束按应用的*默认网络*判断，所以连着 Wi-Fi 时无法运行，默认网络一变成蜂窝就会运行；任务里再重新注册 Wi-Fi 监听。另有 15 分钟周期任务兜底。
  需要应用的**后台数据**未被限制，否则熄屏时蜂窝网络对本应用不可用，任务永远不会运行。
- **为什么不用前台服务**：Shizuku 只在应用 uid 启动 / 从缓存恢复时推送 binder；常驻前台服务会让 uid 一直处于活跃状态，Shizuku 晚于应用启动（如开机）时应用就永远收不到 binder。

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

然后在应用里授权 Shizuku、添加 Wi-Fi、打开开关。

## 调试（仅 debug 构建）

```bash
D="adb shell am broadcast -n dev.autohotspot/.DebugReceiver -a"
$D state    # 打印热点 / Shizuku 状态
$D sstart   # 通过 Shizuku 打开热点
$D stop     # 关闭热点
$D enable / disable
adb logcat -s AutoHotspot
```

调试接收器要求 `DUMP` 权限，只有 adb shell 能触发。

## 注意

- 应用信息 → 移动数据和 WLAN →「**后台数据**」必须开启（界面里会检测并提示）。adb：`adb shell cmd netpolicy remove restrict-background-blacklist <uid>`。
- 建议把电池设为「**不受限制**」；设为「受限」则后台检测完全失效。
- 无 root 时**每次重启手机后 Shizuku 需要再次启动**。Shizuku 的「开机启动（无线调试）」可以自动完成，但无线调试需要连着 Wi-Fi。Shizuku 未运行导致操作失败时，应用会发通知提醒。
- 重启后系统可能要几分钟才把开机广播发给应用，在那之前的 Wi-Fi 切换不会被处理。
- 系统启动热点约需 3 秒（Wi-Fi 芯片初始化），期间界面显示「热点启动中…」。
- 热点开启时，信号较弱的情况下系统可能不会自动回连 Wi-Fi，这时应用自然也不会被触发。

## AI 声明

本项目在 AI 编程助手（Claude，通过可用 adb 操作真机的 agent 环境）的参与下完成，详见 [AI-DECLARATION.md](AI-DECLARATION.md)。

| 阶段 | 级别 | AI 参与度 | 决策记录 |
|---|---|--:|---|
| breakdown 问题拆解 | copilot 人工把关 | 70% | 作者提出目标和每轮需求 / bug（自动关、自动开、状态显示当前网络、「启动中」提示、开机自启）；Agent 拆成技术任务，在关键点请作者确认（授权 Shizuku、重启验证）。 |
| design 方案设计 | copilot 人工把关 | 60% | 作者定产品方向：双向自动化、状态卡显示当前网络、保留 5 秒防抖；Agent 定技术方案：Shizuku 调用 `ITetheringConnector`，放弃前台服务，改用 PendingIntent 网络回调 + JobScheduler 接力。 |
| testing 验收测试 | copilot 人工把关 | 75% | Agent 用 adb 做端到端测试，分析 dumpsys / logcat，截图核对界面；作者完成真机步骤（解锁、连 Wi-Fi、同意重启），并在实际使用中发现 3 个问题。 |
| implementation 技术实现 | auto 模型自主 | 95% | Agent 编写全部代码，两轮 UI 美化交给 subagent；作者未写代码，只按使用反馈要求修改。 |
| review 评审处理 | auto 模型自主 | 85% | Agent 自查并审阅 subagent 产出，发现并修复 Compose 不重组、双标题栏等问题；作者未逐行审代码，只做功能验收。 |
