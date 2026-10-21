package dev.autohotspot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 热点状态，含系统切换过程中的中间态（启动约需 3 秒） */
enum class HotspotState { Off, Starting, On, Stopping }

/** 当前默认网络（实际承载流量的网络）的类型 */
enum class NetworkKind { Wifi, Cellular, Other, None }

/** 界面所需的全部状态；由 MainActivity 在每次 tick 时重新读取 */
@Immutable
data class MainUiState(
    val enabled: Boolean,
    val autoOn: Boolean,
    val ssids: List<String>,
    /** 当前连接的 Wi-Fi SSID（即使默认网络是移动数据也可能有值，如 Wi-Fi 无法上网时） */
    val current: String?,
    /** 默认网络类型：状态卡片据此显示「Wi-Fi 名称」或「移动数据」 */
    val network: NetworkKind,
    /** 运营商名称（移动数据时显示），可能为空 */
    val carrier: String?,
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val notifications: Boolean,
    /** 未被「后台数据」/ 数据节省程序限制。被限制时熄屏后检测不到 Wi-Fi 断开 */
    val backgroundData: Boolean,
    /** 「开关热点时发通知」开关 */
    val statusNotify: Boolean,
    /** 「热点状态」通知渠道可用（应用通知总开关 + 渠道都开着） */
    val statusChannel: Boolean,
    /** 电池「不受限制」（已加入电池优化白名单）。推荐但非必需，可降低后台任务被推迟的概率 */
    val batteryUnrestricted: Boolean,
    val writeSettings: Boolean,
    val shizukuRunning: Boolean,
    val shizukuGranted: Boolean,
    val hotspot: HotspotState,
    val log: List<String>,
) {
    val hotspotOn get() = hotspot == HotspotState.On
    val anyStopMethod get() = shizukuGranted || writeSettings
    val basicMissing get() = listOf(fineLocation, backgroundLocation, notifications, backgroundData || !autoOn).count { !it }
    val needsAttention get() = basicMissing > 0 || !anyStopMethod
    val currentInList get() = current != null && current in ssids

    /** 「权限」卡片中已满足的项数（后台数据在未启用自动打开时视为满足） */
    val basicGranted get() = 4 - basicMissing

    /** 「权限」栏导航角标上的待处理项数 */
    val attentionCount get() = basicMissing + if (anyStopMethod) 0 else 1
}

class MainActions(
    val setEnabled: (Boolean) -> Unit,
    val setAutoOn: (Boolean) -> Unit,
    val setStatusNotify: (Boolean) -> Unit,
    val requestStatusNotifications: () -> Unit,
    val addSsid: (String) -> Unit,
    val removeSsid: (String) -> Unit,
    val requestLocation: () -> Unit,
    val requestBackgroundLocation: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAppSettings: () -> Unit,
    val requestBatteryUnrestricted: () -> Unit,
    val requestShizuku: () -> Unit,
    val requestWriteSettings: () -> Unit,
    val testStopHotspot: () -> Unit,
    val clearLog: () -> Unit,
)

// ───────────────────────────── 导航 ─────────────────────────────

/** 顶层栏目 */
private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Overview("概览", Icons.Outlined.Home, Icons.Rounded.Home),
    Wifi("Wi-Fi", Icons.Outlined.Wifi, Icons.Rounded.Wifi),
    Permissions("权限", Icons.Outlined.Security, Icons.Rounded.Security),
    Log("日志", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Rounded.ReceiptLong),
}

/** 宽度达到此值（如折叠屏内屏、平板）时改用左侧导航栏 */
private val RailBreakpoint = 600.dp

/** 每个栏目各自的滚动与顶部栏状态，切换栏目后保持 */
@OptIn(ExperimentalMaterial3Api::class)
private class TabStates(
    val lists: Map<Tab, LazyListState>,
    val appBars: Map<Tab, TopAppBarState>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberTabStates(): TabStates {
    val lists = Tab.entries.associateWith { rememberLazyListState() }
    val appBars = Tab.entries.associateWith { rememberTopAppBarState() }
    return TabStates(lists, appBars)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: MainUiState, actions: MainActions) {
    var tab by rememberSaveable { mutableStateOf(Tab.Overview) }
    val tabStates = rememberTabStates()
    val scope = rememberCoroutineScope()

    // 非「概览」栏时，系统返回键先回到概览
    BackHandler(enabled = tab != Tab.Overview) { tab = Tab.Overview }

    val select: (Tab) -> Unit = { target ->
        if (target == tab) {
            // 再次点击当前栏目：回到顶部并展开顶部栏
            tabStates.appBars.getValue(target).heightOffset = 0f
            scope.launch { tabStates.lists.getValue(target).animateScrollToItem(0) }
        } else {
            tab = target
        }
    }
    val openPermissions: () -> Unit = {
        tab = Tab.Permissions
        val index = if (state.basicMissing > 0) PERMISSIONS_INDEX else METHODS_INDEX
        scope.launch { tabStates.lists.getValue(Tab.Permissions).scrollToItem(index) }
    }

    // 每个 tab 各自保存 rememberSaveable 状态（如 Wi-Fi 页手动输入的 SSID），切走再切回不丢
    val saveableHolder = rememberSaveableStateHolder()

    val content: @Composable (Modifier) -> Unit = { modifier ->
        AnimatedContent(
            targetState = tab,
            modifier = modifier,
            // Material 淡入淡出（fade through）：旧页快速淡出，新页稍后淡入
            transitionSpec = {
                fadeIn(tween(durationMillis = 210, delayMillis = 90)) togetherWith fadeOut(tween(durationMillis = 90))
            },
            label = "tab",
        ) { current ->
            val list = tabStates.lists.getValue(current)
            val appBar = tabStates.appBars.getValue(current)
            saveableHolder.SaveableStateProvider(current.name) {
                when (current) {
                    Tab.Overview -> OverviewTab(
                        state, actions, list, appBar,
                        onOpenPermissions = openPermissions,
                        onOpenLog = { tab = Tab.Log },
                    )
                    Tab.Wifi -> WifiTab(state, actions, list, appBar)
                    Tab.Permissions -> PermissionsTab(state, actions, list, appBar)
                    Tab.Log -> LogTab(state.log, actions.clearLog, list, appBar)
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= RailBreakpoint) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Spacer(Modifier.height(12.dp))
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = t == tab,
                            onClick = { select(t) },
                            icon = { TabIcon(t, t == tab, state) },
                            label = { Text(t.label) },
                        )
                    }
                }
                // 导航栏已处理起始侧的系统栏 / 刘海区域
                content(
                    Modifier
                        .weight(1f)
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                )
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = { select(t) },
                                icon = { TabIcon(t, t == tab, state) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                content(
                    Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding)
                )
            }
        }
    }
}

@Composable
private fun TabIcon(tab: Tab, selected: Boolean, state: MainUiState) {
    val icon = if (selected) tab.selectedIcon else tab.icon
    val count = if (tab == Tab.Permissions) state.attentionCount else 0
    if (count > 0) {
        BadgedBox(badge = { Badge { Text("$count") } }) {
            Icon(icon, contentDescription = "${tab.label}，$count 项待处理")
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}
