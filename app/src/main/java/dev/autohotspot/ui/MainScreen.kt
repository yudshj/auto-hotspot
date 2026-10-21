package dev.autohotspot.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.ShareLocation
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.WifiTetheringOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** LazyColumn 中的各个区块；按此顺序显示，跳转目标的索引由列表动态计算 */
private enum class Block { Status, Banner, Automation, Wifi, Permissions, Methods, Log }

/** 宽屏（如折叠屏内屏）上限制内容宽度，避免卡片被拉得过宽 */
private val MaxContentWidth = 640.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: MainUiState, actions: MainActions) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val blocks = Block.entries.filter { it != Block.Banner || state.needsAttention }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text("热点自动关") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(blocks, key = { it.name }) { block ->
                val m = Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .animateItem()
                when (block) {
                    Block.Status -> StatusCard(state, m)
                    Block.Banner -> AttentionBanner(state, m) {
                        val target = if (state.basicMissing > 0) Block.Permissions else Block.Methods
                        val index = blocks.indexOf(target)
                        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                    }
                    Block.Automation -> AutomationSection(state, actions, m)
                    Block.Wifi -> WifiListSection(state, actions, m)
                    Block.Permissions -> PermissionsSection(state, actions, m)
                    Block.Methods -> MethodsSection(state, actions, m)
                    Block.Log -> LogSection(state.log, actions.clearLog, m)
                }
            }
        }
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
            currentInList -> "Wi-Fi"
            else -> "Wi-Fi · 不在列表中"
        },
        inList = currentInList,
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

/** 状态卡片底部的一句话总结：结合当前网络、热点状态与两项自动化 */
private fun MainUiState.summary(): Pair<SummaryTone, String> {
    if (hotspot == HotspotState.Starting) return SummaryTone.Neutral to "热点启动中，通常需要几秒…"
    if (hotspot == HotspotState.Stopping) return SummaryTone.Neutral to "热点关闭中…"
    if (!enabled && !autoOn) return SummaryTone.Neutral to "两项自动化都已关闭，热点不会被自动控制"
    return when (network) {
        NetworkKind.Wifi -> when {
            current == null -> SummaryTone.Neutral to "无法识别 Wi-Fi 名称，暂时不会自动关闭热点"
            !currentInList -> SummaryTone.Neutral to "「$current」不在列表中，热点不受影响"
            !enabled -> SummaryTone.Neutral to "在「$current」，但连接时关闭热点已停用"
            hotspotOn -> SummaryTone.Warn to "在「$current」但热点仍开启，请检查关闭热点的方式"
            else -> SummaryTone.Good to "在「$current」，热点保持关闭"
        }
        NetworkKind.Cellular -> when {
            !autoOn -> SummaryTone.Neutral to "使用移动数据中，自动打开热点已停用"
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
    val active = state.enabled || state.autoOn
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

// ───────────────────────────── 通用组件 ─────────────────────────────

@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content()
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun transparentListItemColors() =
    ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun CardDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 列表项前的圆形图标底座 */
@Composable
private fun IconBadge(
    icon: ImageVector,
    container: Color,
    content: Color,
    size: Dp = 40.dp,
) {
    val bg by animateColorAsState(container, label = "badgeBg")
    val fg by animateColorAsState(content, label = "badgeFg")
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun Chip(text: String, icon: ImageVector?, container: Color, content: Color) {
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Row(
            Modifier.padding(start = if (icon != null) 6.dp else 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun CheckSwitch(checked: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = null, // 整行可点，由外层 toggleable 处理
        thumbContent = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else null,
    )
}

// ───────────────────────────── 自动化 ─────────────────────────────

@Composable
private fun AutomationItem(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        leadingContent = {
            IconBadge(
                icon,
                container = if (checked) cs.primary else cs.surfaceContainerHighest,
                content = if (checked) cs.onPrimary else cs.onSurfaceVariant,
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
    val on = listOf(state.enabled, state.autoOn).count { it }
    Section("自动化", modifier, trailing = "已开启 $on/2") {
        SectionCard {
            AutomationItem(
                icon = Icons.Rounded.WifiTetheringOff,
                title = "连接到列表中的 Wi-Fi 时关闭热点",
                description = "连上下方列表中任一 Wi-Fi 即关闭热点",
                checked = state.enabled,
                onCheckedChange = actions.setEnabled,
            )
            CardDivider()
            AutomationItem(
                icon = Icons.Rounded.WifiTethering,
                title = "断开 Wi-Fi 后自动打开热点",
                description = "从 Wi-Fi 切换到移动数据时打开热点；在移动数据下手动关闭热点后不会再次打开",
                checked = state.autoOn,
                onCheckedChange = actions.setAutoOn,
            )
            CardDivider()
            AutomationItem(
                icon = Icons.Rounded.NotificationsActive,
                title = "开关热点时发通知",
                description = "自动打开/关闭热点时在通知栏显示进度和结果（无声），可据此确认后台仍在工作",
                checked = state.statusNotify,
                onCheckedChange = actions.setStatusNotify,
            )
        }
    }
}

// ───────────────────────────── Wi-Fi 列表 ─────────────────────────────

@Composable
private fun WifiListSection(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    var input by rememberSaveable { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme

    Section(
        "Wi-Fi 列表", modifier,
        trailing = if (state.ssids.isEmpty()) null else "${state.ssids.size} 个",
    ) {
        SectionCard(Modifier.animateContentSize()) {
            if (state.ssids.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconBadge(
                        Icons.Outlined.WifiFind,
                        container = cs.secondaryContainer,
                        content = cs.onSecondaryContainer,
                        size = 56.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("列表为空", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "添加 Wi-Fi 后，连接到这些网络时会自动关闭热点",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                state.ssids.forEachIndexed { i, s ->
                    if (i > 0) CardDivider()
                    val isCurrent = s == state.current
                    ListItem(
                        leadingContent = {
                            IconBadge(
                                Icons.Rounded.Wifi,
                                container = if (isCurrent) cs.primaryContainer else cs.surfaceContainerHighest,
                                content = if (isCurrent) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            )
                        },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    s, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (isCurrent) {
                                    Spacer(Modifier.width(8.dp))
                                    Chip("当前", icon = null, container = cs.primary, content = cs.onPrimary)
                                }
                            }
                        },
                        supportingContent = if (isCurrent) {
                            { Text("已连接") }
                        } else null,
                        trailingContent = {
                            IconButton(onClick = { actions.removeSsid(s) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "删除 $s")
                            }
                        },
                        colors = transparentListItemColors(),
                    )
                }
            }

            CardDivider()

            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val canAddCurrent = state.current != null && state.current !in state.ssids
                FilledTonalButton(
                    enabled = canAddCurrent,
                    onClick = { state.current?.let(actions.addSsid) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(
                        if (state.currentInList) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(
                        when {
                            state.current == null -> "添加当前 Wi-Fi"
                            state.currentInList -> "当前 Wi-Fi 已在列表中"
                            else -> "添加当前 Wi-Fi「${state.current}」"
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                val submit = { actions.addSsid(input); input = "" }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("手动输入 SSID") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) submit() }),
                    trailingIcon = {
                        IconButton(onClick = submit, enabled = input.isNotBlank()) {
                            Icon(Icons.Rounded.Add, contentDescription = "添加")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ───────────────────────────── 权限 ─────────────────────────────

private enum class PermStatus { Granted, Missing, Optional }

@Composable
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String,
    status: PermStatus,
    actionLabel: String = "授予",
    onGrant: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ListItem(
        leadingContent = {
            IconBadge(
                icon,
                container = when (status) {
                    PermStatus.Granted -> cs.secondaryContainer
                    PermStatus.Missing -> cs.errorContainer
                    PermStatus.Optional -> cs.surfaceContainerHighest
                },
                content = when (status) {
                    PermStatus.Granted -> cs.onSecondaryContainer
                    PermStatus.Missing -> cs.onErrorContainer
                    PermStatus.Optional -> cs.onSurfaceVariant
                },
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            when (status) {
                PermStatus.Granted -> Icon(Icons.Rounded.CheckCircle, contentDescription = "已授予", tint = cs.primary)
                PermStatus.Missing -> FilledTonalButton(onClick = onGrant) { Text(actionLabel) }
                PermStatus.Optional -> TextButton(onClick = onGrant) { Text(actionLabel) }
            }
        },
        colors = transparentListItemColors(),
    )
}

@Composable
private fun PermissionsSection(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    Section("权限", modifier, trailing = "已满足 ${state.basicGranted}/4") {
        SectionCard {
            PermissionItem(
                icon = Icons.Rounded.LocationOn,
                title = "精确位置",
                description = "读取当前连接的 Wi-Fi 名称（SSID）",
                status = if (state.fineLocation) PermStatus.Granted else PermStatus.Missing,
                onGrant = actions.requestLocation,
            )
            CardDivider()
            PermissionItem(
                icon = Icons.Rounded.ShareLocation,
                title = "后台位置「始终允许」",
                description = if (state.fineLocation) "在后台也能读取 SSID" else "在后台也能读取 SSID（需先授予精确位置）",
                status = if (state.backgroundLocation) PermStatus.Granted else PermStatus.Missing,
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
                    !state.autoOn -> PermStatus.Optional
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
                    state.shizukuRunning -> "运行中，未授权"
                    else -> "未运行 · 请打开 Shizuku 启动服务（每次重启手机后都需要）"
                },
                status = when {
                    state.shizukuGranted -> PermStatus.Granted
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
                description = "备用方式，Android 16+ 上可能被系统忽略",
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

// ───────────────────────────── 日志 ─────────────────────────────

private const val COLLAPSED_LOG_LINES = 3
private const val MAX_LOG_LINES = 40

@Composable
private fun LogSection(log: List<String>, onClear: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "logChevron")
    val canExpand = log.size > COLLAPSED_LOG_LINES

    Section("日志", modifier) {
        SectionCard(Modifier.animateContentSize()) {
            ListItem(
                modifier = if (canExpand) Modifier.toggleable(
                    value = expanded, role = Role.Button, onValueChange = { expanded = it },
                ) else Modifier,
                leadingContent = {
                    IconBadge(
                        Icons.AutoMirrored.Rounded.ReceiptLong,
                        container = cs.surfaceContainerHighest,
                        content = cs.onSurfaceVariant,
                    )
                },
                headlineContent = { Text("运行日志") },
                supportingContent = { Text(if (log.isEmpty()) "暂无记录" else "共 ${log.size} 条") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClear, enabled = log.isNotEmpty()) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "清空")
                        }
                        if (canExpand) {
                            Icon(
                                Icons.Rounded.ExpandMore,
                                contentDescription = if (expanded) "收起" else "展开",
                                modifier = Modifier.rotate(rotation),
                            )
                        }
                    }
                },
                colors = transparentListItemColors(),
            )
            AnimatedVisibility(
                visible = log.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val lines = if (expanded) log.take(MAX_LOG_LINES) else log.take(COLLAPSED_LOG_LINES)
                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(cs.surfaceContainerHighest)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (expanded) "最近 ${lines.size} 条" else "最新",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                    lines.forEach {
                        Text(
                            it,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = cs.onSurface,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
