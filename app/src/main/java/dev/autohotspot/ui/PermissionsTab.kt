package dev.autohotspot.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.ShareLocation
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiTetheringOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** 「权限」栏中各区块在列表里的索引，供概览页的提示卡片跳转 */
internal const val PERMISSIONS_INDEX = 0
internal const val METHODS_INDEX = 1

/** 「权限」栏：运行所需权限 + 控制热点的方式 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionsTab(
    state: MainUiState,
    actions: MainActions,
    listState: LazyListState,
    appBarState: TopAppBarState,
    modifier: Modifier = Modifier,
) {
    TabPage("权限", listState, appBarState, modifier) {
        // 顺序需与 PERMISSIONS_INDEX / METHODS_INDEX 一致
        item(key = "permissions") { PermissionsSection(state, actions, Modifier.contentWidth()) }
        item(key = "methods") { MethodsSection(state, actions, Modifier.contentWidth()) }
    }
}

@Composable
private fun PermissionsSection(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    Section("运行所需权限", modifier, trailing = "已满足 ${state.basicGranted}/4") {
        SectionCard {
            PermissionItem(
                icon = Icons.Rounded.LocationOn,
                title = "精确位置",
                description = "读取当前连接的 Wi-Fi 名称（SSID）",
                status = when {
                    state.fineLocation -> PermStatus.Granted
                    !state.locationRequired -> PermStatus.Optional
                    else -> PermStatus.Missing
                },
                onGrant = actions.requestLocation,
            )
            CardDivider()
            PermissionItem(
                icon = Icons.Rounded.ShareLocation,
                title = "后台位置「始终允许」",
                description = if (state.fineLocation) "在后台也能读取 SSID" else "在后台也能读取 SSID（需先授予精确位置）",
                status = when {
                    state.backgroundLocation -> PermStatus.Granted
                    !state.locationRequired -> PermStatus.Optional
                    else -> PermStatus.Missing
                },
                onGrant = actions.requestBackgroundLocation,
            )
            CardDivider()
            PermissionItem(
                icon = Icons.Rounded.Notifications,
                title = "通知",
                description = "允许本应用发通知：状态通知与失败提醒（如 Shizuku 未运行）都需要",
                status = if (state.notifications) PermStatus.Granted else PermStatus.Missing,
                onGrant = actions.requestNotifications,
            )
            CardDivider()
            PermissionItem(
                icon = Icons.Rounded.NotificationsActive,
                title = "状态通知",
                description = when {
                    !state.notifications -> "需要先允许通知；点击申请"
                    state.statusChannel -> "「热点状态」通知已开启：自动开/关热点时会在通知栏提示"
                    else -> "「热点状态」通知渠道已被关闭，点击去开启"
                },
                status = when {
                    state.notifications && state.statusChannel -> PermStatus.Granted
                    state.statusNotify -> PermStatus.Missing
                    else -> PermStatus.Optional
                },
                actionLabel = if (state.notifications) "去开启" else "申请",
                onGrant = actions.requestStatusNotifications,
            )
            CardDivider()
            PermissionItem(
                icon = Icons.Rounded.DataUsage,
                title = "后台数据",
                description = when {
                    state.backgroundData -> "未受限制，熄屏时也能检测到 Wi-Fi 切换到移动数据"
                    else -> "已被限制：熄屏后检测不到 Wi-Fi 切换到移动数据。请在 应用信息 → 移动数据和 WLAN 中开启「后台数据」" +
                        "（若开着数据节省程序，还需开启「不受数据流量限制」）"
                },
                status = when {
                    state.backgroundData -> PermStatus.Granted
                    !state.enabled -> PermStatus.Optional
                    else -> PermStatus.Missing
                },
                actionLabel = "去设置",
                onGrant = actions.openAppSettings,
            )
            CardDivider()
            PermissionItem(
                icon = Icons.Rounded.BatteryFull,
                title = "电池「不受限制」（推荐）",
                description = if (state.batteryUnrestricted) {
                    "不受电池优化限制，后台检测更及时"
                } else {
                    "当前为「优化」：深度休眠时后台检测可能被推迟。设为「受限」则完全无法自动切换"
                },
                status = if (state.batteryUnrestricted) PermStatus.Granted else PermStatus.Optional,
                actionLabel = "去设置",
                onGrant = actions.requestBatteryUnrestricted,
            )
        }
    }
}

@Composable
private fun MethodsSection(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Section(
        "控制热点的方式", modifier,
        trailing = if (state.anyStopMethod) "可用" else "未配置",
    ) {
        SectionCard {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Info, contentDescription = null,
                    tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "至少需要一种，推荐使用 Shizuku",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            PermissionItem(
                icon = Icons.Rounded.Terminal,
                title = "Shizuku",
                description = when {
                    state.shizukuGranted -> "已授权 · 手机重启后需确保 Shizuku 再次启动"
                    state.shizukuRunning -> "运行中，未授权" + if (state.enabled) " · 自动打开热点需要它" else ""
                    else -> "未运行 · 请打开 Shizuku 启动服务（每次重启手机后都需要）"
                },
                status = when {
                    state.shizukuGranted -> PermStatus.Granted
                    state.shizukuRequired -> PermStatus.Missing
                    state.anyStopMethod -> PermStatus.Optional
                    else -> PermStatus.Missing
                },
                actionLabel = "授权",
                onGrant = actions.requestShizuku,
            )
            CardDivider()
            PermissionItem(
                icon = Icons.Rounded.SettingsSuggest,
                title = "修改系统设置",
                description = "备用方式，只能关闭热点，Android 16+ 上可能被系统忽略",
                status = when {
                    state.writeSettings -> PermStatus.Granted
                    state.anyStopMethod -> PermStatus.Optional
                    else -> PermStatus.Missing
                },
                actionLabel = "去设置",
                onGrant = actions.requestWriteSettings,
            )
            if (!state.shizukuGranted && state.writeSettings && Build.VERSION.SDK_INT >= 36) {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(cs.tertiaryContainer)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Warning, contentDescription = null,
                        tint = cs.onTertiaryContainer, modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "仅靠「修改系统设置」在本机上可能无效，建议授权 Shizuku",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onTertiaryContainer,
                    )
                }
            }
            OutlinedButton(
                onClick = actions.testStopHotspot,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    Icons.Rounded.WifiTetheringOff, contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("测试：立即关闭热点")
            }
        }
    }
}
