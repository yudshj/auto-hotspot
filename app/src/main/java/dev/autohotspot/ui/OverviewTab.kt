package dev.autohotspot.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.WifiTetheringOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 「概览」栏：当前状态、待处理提示、自动化开关与最近一次操作 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverviewTab(
    state: MainUiState,
    actions: MainActions,
    listState: LazyListState,
    appBarState: TopAppBarState,
    onOpenPermissions: () -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TabPage("热点自动关", listState, appBarState, modifier) {
        item(key = "status") { StatusCard(state, Modifier.contentWidth().animateItem()) }
        if (state.needsAttention) {
            item(key = "banner") {
                AttentionBanner(state, Modifier.contentWidth().animateItem(), onClick = onOpenPermissions)
            }
        }
        item(key = "automation") { AutomationSection(state, actions, Modifier.contentWidth().animateItem()) }
        item(key = "recent") { RecentLogSection(state.log, onOpenLog, Modifier.contentWidth().animateItem()) }
    }
}

// ───────────────────────────── 状态卡片 ─────────────────────────────

/** 状态卡片中「当前网络」一栏要显示的内容 */
private data class NetworkInfo(
    val kind: NetworkKind,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val inList: Boolean,
)

private fun MainUiState.networkInfo(): NetworkInfo = when (network) {
    NetworkKind.Wifi -> NetworkInfo(
        kind = network,
        icon = Icons.Rounded.Wifi,
        title = current ?: "Wi-Fi",
        subtitle = when {
            current == null && !fineLocation -> "Wi-Fi · 需要位置权限才能显示名称"
            current == null -> "Wi-Fi · 暂时读不到名称"
            currentInList || anyWifi -> "Wi-Fi"
            else -> "Wi-Fi · 不在列表中"
        },
        inList = currentInList && !anyWifi,
    )
    NetworkKind.Cellular -> NetworkInfo(
        kind = network,
        icon = Icons.Rounded.SignalCellularAlt,
        title = "移动数据",
        subtitle = carrier ?: "蜂窝网络",
        inList = false,
    )
    NetworkKind.Other -> NetworkInfo(
        kind = network,
        icon = Icons.Rounded.Lan,
        title = "其他网络",
        subtitle = "既不是 Wi-Fi 也不是移动数据",
        inList = false,
    )
    NetworkKind.None -> NetworkInfo(
        kind = network,
        icon = Icons.Rounded.WifiOff,
        title = "未联网",
        subtitle = "当前没有可用的网络连接",
        inList = false,
    )
}

private enum class SummaryTone { Good, Neutral, Warn }

/** 状态卡片底部的一句话总结：结合当前网络、热点状态与自动化开关 */
private fun MainUiState.summary(): Pair<SummaryTone, String> {
    if (hotspot == HotspotState.Starting) return SummaryTone.Neutral to "热点启动中，通常需要几秒…"
    if (hotspot == HotspotState.Stopping) return SummaryTone.Neutral to "热点关闭中…"
    if (!enabled) return SummaryTone.Neutral to "自动开关热点已关闭，热点不会被自动控制"
    return when (network) {
        NetworkKind.Wifi -> when {
            !anyWifi && current == null -> SummaryTone.Neutral to "无法识别 Wi-Fi 名称，暂时不会自动关闭热点"
            !currentMatches -> SummaryTone.Neutral to "「$current」不在列表中，热点不受影响"
            hotspotOn -> SummaryTone.Warn to "在「${current ?: "Wi-Fi"}」但热点仍开启，请检查关闭热点的方式"
            anyWifi -> SummaryTone.Good to "在任意 Wi-Fi，热点保持关闭"
            else -> SummaryTone.Good to "在「${current ?: "Wi-Fi"}」，热点保持关闭"
        }
        NetworkKind.Cellular -> when {
            hotspotOn -> SummaryTone.Good to "使用移动数据中，热点已开启"
            else -> SummaryTone.Neutral to "使用移动数据中；下次从 Wi-Fi 切到移动数据时会自动打开热点"
        }
        NetworkKind.Other -> SummaryTone.Neutral to "通过其他网络联网，不会触发自动化"
        NetworkKind.None -> SummaryTone.Neutral to "未联网，连接 Wi-Fi 或移动数据后自动化才会生效"
    }
}

@Composable
private fun StatusCard(state: MainUiState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val active = state.enabled
    val net = state.networkInfo()
    val (tone, summary) = state.summary()

    // 卡片色调随网络类型变化：Wi-Fi 用主色、移动数据用第三色，其他/未联网/自动化关闭时用中性色
    val (targetContainer, targetContent) = when {
        !active -> cs.surfaceContainerHigh to cs.onSurface
        net.kind == NetworkKind.Wifi -> cs.primaryContainer to cs.onPrimaryContainer
        net.kind == NetworkKind.Cellular -> cs.tertiaryContainer to cs.onTertiaryContainer
        else -> cs.surfaceContainerHigh to cs.onSurface
    }
    val container by animateColorAsState(targetContainer, label = "statusContainer")
    val content by animateColorAsState(targetContent, label = "statusContent")
    val accent by animateColorAsState(
        when {
            !active -> cs.onSurfaceVariant
            net.kind == NetworkKind.Wifi -> cs.primary
            net.kind == NetworkKind.Cellular -> cs.tertiary
            else -> cs.onSurfaceVariant
        },
        label = "statusAccent",
    )
    val onAccent by animateColorAsState(
        when {
            !active -> cs.surfaceContainerHigh
            net.kind == NetworkKind.Wifi -> cs.onPrimary
            net.kind == NetworkKind.Cellular -> cs.onTertiary
            else -> cs.surfaceContainerHigh
        },
        label = "statusOnAccent",
    )

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "当前网络",
                    style = MaterialTheme.typography.labelLarge,
                    color = content.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f),
                )
                HotspotPill(state.hotspot)
            }

            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = net,
                contentKey = { it.kind to it.title },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "network",
            ) { info ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(info.icon, contentDescription = null, tint = onAccent, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            info.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                info.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = content.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (info.inList) {
                                Spacer(Modifier.width(8.dp))
                                Chip(
                                    text = "在列表中",
                                    icon = Icons.Rounded.Check,
                                    container = accent,
                                    content = onAccent,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Surface(
                shape = MaterialTheme.shapes.large,
                color = cs.surface.copy(alpha = 0.55f),
                contentColor = cs.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when (tone) {
                            SummaryTone.Good -> Icons.Rounded.CheckCircle
                            SummaryTone.Neutral -> Icons.Rounded.Info
                            SummaryTone.Warn -> Icons.Rounded.Warning
                        },
                        contentDescription = null,
                        tint = when (tone) {
                            SummaryTone.Good -> cs.primary
                            SummaryTone.Neutral -> cs.onSurfaceVariant
                            SummaryTone.Warn -> cs.error
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    AnimatedContent(
                        targetState = summary,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "summary",
                    ) {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun HotspotPill(hotspot: HotspotState) {
    val on = hotspot == HotspotState.On
    val busy = hotspot == HotspotState.Starting || hotspot == HotspotState.Stopping
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (on) cs.inverseSurface else cs.surface.copy(alpha = 0.55f), label = "pillContainer"
    )
    val content by animateColorAsState(
        if (on) cs.inverseOnSurface else cs.onSurfaceVariant, label = "pillContent"
    )
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            } else {
                Icon(
                    if (on) Icons.Rounded.WifiTethering else Icons.Rounded.WifiTetheringOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            AnimatedContent(targetState = hotspot, label = "pillText") { h ->
                Text(
                    when (h) {
                        HotspotState.On -> "热点已开启"
                        HotspotState.Off -> "热点已关闭"
                        HotspotState.Starting -> "热点启动中…"
                        HotspotState.Stopping -> "热点关闭中…"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ───────────────────────────── 提示卡片 ─────────────────────────────

@Composable
private fun AttentionBanner(state: MainUiState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val parts = buildList {
        if (state.basicMissing > 0) add("${state.basicMissing} 项必需权限未授予")
        if (!state.anyStopMethod) add("尚无可用的关闭热点方式")
        else if (state.shizukuRequired) add("自动打开热点需要 Shizuku")
    }
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = cs.errorContainer,
            contentColor = cs.onErrorContainer,
        ),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Warning, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("需要处理", style = MaterialTheme.typography.titleSmall)
                Text(
                    parts.joinToString("，") + "，自动化可能无法工作",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(
                onClick = onClick,
                colors = ButtonDefaults.textButtonColors(contentColor = cs.onErrorContainer),
            ) { Text("查看") }
        }
    }
}

// ───────────────────────────── 自动化 ─────────────────────────────

@Composable
private fun AutomationItem(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val on = checked && enabled
    ListItem(
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .alpha(if (enabled) 1f else 0.5f),
        leadingContent = {
            IconBadge(
                icon,
                container = if (on) cs.primary else cs.surfaceContainerHighest,
                content = if (on) cs.onPrimary else cs.onSurfaceVariant,
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { CheckSwitch(checked) },
        colors = transparentListItemColors(),
    )
}

@Composable
private fun AutomationSection(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    val switches = listOf(state.enabled, state.realtime, state.statusNotify)
    // 总开关关着时子选项不生效，不计数
    val trailing = if (state.enabled) "已开启 ${switches.count { it }}/${switches.size}" else "已关闭"
    Section("自动化", modifier, trailing = trailing) {
        SectionCard {
            AutomationItem(
                icon = Icons.Rounded.WifiTethering,
                title = "自动开关热点",
                description = "断开 Wi-Fi、改用移动数据时打开热点；" +
                    (if (state.anyWifi) "连上任何 Wi-Fi 时关闭" else "连上「Wi-Fi」栏列表中的网络时关闭") +
                    "。在移动数据下手动关掉热点后不会再次打开",
                checked = state.enabled,
                onCheckedChange = actions.setEnabled,
            )
            CardDivider()
            AutomationItem(
                enabled = state.enabled,
                icon = Icons.Rounded.Bolt,
                title = "实时监听",
                description = "常驻一条静默通知，Wi-Fi 一变就立即处理；关掉后靠系统事件和定时检查，" +
                    "连着没有互联网的 Wi-Fi、或流量关着时换 Wi-Fi，可能晚几分钟",
                checked = state.realtime,
                onCheckedChange = actions.setRealtime,
            )
            CardDivider()
            AutomationItem(
                enabled = state.enabled,
                icon = Icons.Rounded.NotificationsActive,
                title = "开关热点时发通知",
                description = "自动打开/关闭热点时在通知栏显示进度和结果（无声），可据此确认后台仍在工作",
                checked = state.statusNotify,
                onCheckedChange = actions.setStatusNotify,
            )
        }
    }
}

// ───────────────────────────── 最近一次操作 ─────────────────────────────

@Composable
private fun RecentLogSection(log: List<String>, onOpenLog: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val latest = log.firstOrNull()?.let(::parseLogLine)
    Section("最近一次操作", modifier) {
        SectionCard {
            ListItem(
                modifier = Modifier.clickable(onClickLabel = "查看日志", onClick = onOpenLog),
                leadingContent = {
                    IconBadge(
                        Icons.AutoMirrored.Rounded.ReceiptLong,
                        container = cs.surfaceContainerHighest,
                        content = cs.onSurfaceVariant,
                    )
                },
                overlineContent = latest?.time?.let { { Text(it, fontFamily = FontFamily.Monospace) } },
                headlineContent = {
                    if (latest == null) {
                        Text("暂无记录")
                    } else {
                        Text(
                            latest.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                supportingContent = if (log.size > 1) {
                    { Text("共 ${log.size} 条，查看全部日志") }
                } else null,
                trailingContent = {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                },
                colors = transparentListItemColors(),
            )
        }
    }
}
