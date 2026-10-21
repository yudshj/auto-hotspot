package dev.autohotspot.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.WifiTetheringOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 界面所需的全部状态；由 MainActivity 在每次 tick 时重新读取 */
@Immutable
data class MainUiState(
    val enabled: Boolean,
    val autoOn: Boolean,
    val ssids: List<String>,
    val current: String?,
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val notifications: Boolean,
    /** 未被「后台数据」/ 数据节省程序限制。被限制时熄屏后检测不到 Wi-Fi 断开 */
    val backgroundData: Boolean,
    val writeSettings: Boolean,
    val shizukuRunning: Boolean,
    val shizukuGranted: Boolean,
    val hotspotOn: Boolean,
    val log: List<String>,
) {
    val anyStopMethod get() = shizukuGranted || writeSettings
    val basicMissing get() = listOf(fineLocation, backgroundLocation, notifications, backgroundData || !autoOn).count { !it }
    val needsAttention get() = basicMissing > 0 || !anyStopMethod
    val currentInList get() = current != null && current in ssids
}

class MainActions(
    val setEnabled: (Boolean) -> Unit,
    val setAutoOn: (Boolean) -> Unit,
    val addSsid: (String) -> Unit,
    val removeSsid: (String) -> Unit,
    val requestLocation: () -> Unit,
    val requestBackgroundLocation: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAppSettings: () -> Unit,
    val requestShizuku: () -> Unit,
    val requestWriteSettings: () -> Unit,
    val testStopHotspot: () -> Unit,
    val clearLog: () -> Unit,
)

// LazyColumn 中各项的固定索引（用于提示卡片跳转）
private const val INDEX_PERMISSIONS = 4
private const val INDEX_METHODS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: MainUiState, actions: MainActions) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
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
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "hero") { StatusHero(state, actions) }
            if (state.needsAttention) {
                item(key = "banner") {
                    AttentionBanner(state, Modifier.animateItem()) {
                        scope.launch {
                            // 横幅存在时：hero=0, banner=1, rules=2, wifi=3, perms=4, methods=5
                            listState.animateScrollToItem(
                                if (state.basicMissing > 0) INDEX_PERMISSIONS else INDEX_METHODS
                            )
                        }
                    }
                }
            }
            item(key = "rules") { RulesSection(state, actions) }
            item(key = "wifi") { WifiListSection(state, actions) }
            item(key = "perms") { PermissionsSection(state, actions) }
            item(key = "methods") { MethodsSection(state, actions) }
            item(key = "log") { LogSection(state.log, actions.clearLog) }
        }
    }
}

// ───────────────────────────── 状态卡片 ─────────────────────────────

@Composable
private fun StatusHero(state: MainUiState, actions: MainActions) {
    val cs = MaterialTheme.colorScheme
    val active = state.enabled
    val container by animateColorAsState(
        if (active) cs.primaryContainer else cs.surfaceContainerHigh, label = "heroContainer"
    )
    val content = if (active) cs.onPrimaryContainer else cs.onSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .toggleable(
                        value = state.enabled,
                        role = Role.Switch,
                        onValueChange = actions.setEnabled,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (active) cs.primary else cs.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (active) Icons.Rounded.Shield else Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = if (active) cs.onPrimary else cs.onSurfaceVariant,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (active) "正在监控" else "监控已关闭",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (active) "连接到列表中的 Wi-Fi 时自动关闭热点" else "打开开关以启用自动关闭",
                        style = MaterialTheme.typography.bodyMedium,
                        color = content.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.enabled, onCheckedChange = null)
            }

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val wifiValue = state.current
                    ?: if (state.fineLocation) "未连接 / 未知" else "需要位置权限"
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = if (state.current != null) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                    label = "当前 Wi-Fi",
                    value = wifiValue,
                    caption = when {
                        state.current == null -> null
                        state.currentInList -> "在列表中"
                        else -> "不在列表中"
                    },
                    highlight = state.currentInList,
                )
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = if (state.hotspotOn) Icons.Rounded.WifiTethering else Icons.Rounded.WifiTetheringOff,
                    label = "热点",
                    value = if (state.hotspotOn) "已开启" else "已关闭",
                    caption = null,
                    highlight = state.hotspotOn,
                )
            }
        }
    }
}

@Composable
private fun StatusTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    caption: String?,
    highlight: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLowest.copy(alpha = 0.7f),
        contentColor = cs.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(
                icon, contentDescription = null,
                tint = if (highlight) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (highlight) cs.primary else cs.onSurfaceVariant,
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
        modifier = modifier.fillMaxWidth(),
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
                    parts.joinToString("，") + "，自动关闭可能无法工作",
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
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

// ───────────────────────────── 规则 ─────────────────────────────

@Composable
private fun RulesSection(state: MainUiState, actions: MainActions) {
    Section("规则") {
        SectionCard {
            ListItem(
                modifier = Modifier.toggleable(
                    value = state.autoOn,
                    role = Role.Switch,
                    onValueChange = actions.setAutoOn,
                ),
                leadingContent = { Icon(Icons.Rounded.SignalCellularAlt, contentDescription = null) },
                headlineContent = { Text("断开 Wi-Fi 后自动打开热点") },
                supportingContent = {
                    Text("从 Wi-Fi 切换到移动数据时打开热点；在流量下手动关掉热点不会被再次打开")
                },
                trailingContent = { Switch(checked = state.autoOn, onCheckedChange = null) },
                colors = transparentListItemColors(),
            )
        }
    }
}

// ───────────────────────────── Wi-Fi 列表 ─────────────────────────────

@Composable
private fun WifiListSection(state: MainUiState, actions: MainActions) {
    var input by rememberSaveable { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme

    Section("Wi-Fi 列表") {
        SectionCard {
            if (state.ssids.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(cs.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.WifiFind, contentDescription = null,
                            tint = cs.onSecondaryContainer, modifier = Modifier.size(28.dp),
                        )
                    }
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
                            Icon(
                                Icons.Rounded.Wifi, contentDescription = null,
                                tint = if (isCurrent) cs.primary else cs.onSurfaceVariant,
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
                                    CurrentBadge()
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
                        Icons.Rounded.Add, contentDescription = null,
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
                    keyboardActions = KeyboardActions(onDone = { submit() }),
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

@Composable
private fun CurrentBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            "当前",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ───────────────────────────── 权限 ─────────────────────────────

private enum class PermStatus { Granted, Missing, Optional }

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    status: PermStatus,
    onGrant: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ListItem(
        leadingContent = {
            when (status) {
                PermStatus.Granted -> Icon(Icons.Rounded.CheckCircle, contentDescription = "已授予", tint = cs.primary)
                PermStatus.Missing -> Icon(Icons.Rounded.Error, contentDescription = "未授予", tint = cs.error)
                PermStatus.Optional -> Icon(
                    Icons.Outlined.RadioButtonUnchecked, contentDescription = "未授予（可选）", tint = cs.outline,
                )
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = if (status != PermStatus.Granted) {
            {
                if (status == PermStatus.Missing) {
                    FilledTonalButton(onClick = onGrant) { Text("授予") }
                } else {
                    TextButton(onClick = onGrant) { Text("授予") }
                }
            }
        } else null,
        colors = transparentListItemColors(),
    )
}

@Composable
private fun PermissionsSection(state: MainUiState, actions: MainActions) {
    Section("权限") {
        SectionCard {
            PermissionItem(
                title = "精确位置",
                description = "读取当前连接的 Wi-Fi 名称（SSID）",
                status = if (state.fineLocation) PermStatus.Granted else PermStatus.Missing,
                onGrant = actions.requestLocation,
            )
            CardDivider()
            PermissionItem(
                title = "后台位置「始终允许」",
                description = if (state.fineLocation) "在后台也能读取 SSID" else "在后台也能读取 SSID（需先授予精确位置）",
                status = if (state.backgroundLocation) PermStatus.Granted else PermStatus.Missing,
                onGrant = actions.requestBackgroundLocation,
            )
            CardDivider()
            PermissionItem(
                title = "通知",
                description = "无法自动关闭热点时（如 Shizuku 未运行）提醒你",
                status = if (state.notifications) PermStatus.Granted else PermStatus.Missing,
                onGrant = actions.requestNotifications,
            )
            CardDivider()
            PermissionItem(
                title = "后台数据",
                description = when {
                    state.backgroundData -> "允许后台使用移动数据，熄屏时也能检测到 Wi-Fi 断开"
                    else -> "已被限制：熄屏后检测不到 Wi-Fi 断开。请在 应用信息 → 移动数据和 WLAN 中开启「后台数据」" +
                        "（若开着数据节省程序，还需开启「不受数据流量限制」）"
                },
                status = when {
                    state.backgroundData -> PermStatus.Granted
                    !state.autoOn -> PermStatus.Optional
                    else -> PermStatus.Missing
                },
                onGrant = actions.openAppSettings,
            )
        }
    }
}

@Composable
private fun MethodsSection(state: MainUiState, actions: MainActions) {
    val cs = MaterialTheme.colorScheme
    Section("关闭热点的方式") {
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
                onGrant = actions.requestShizuku,
            )
            CardDivider()
            PermissionItem(
                title = "修改系统设置",
                description = "备用方式，Android 16+ 上可能被系统忽略",
                status = when {
                    state.writeSettings -> PermStatus.Granted
                    state.anyStopMethod -> PermStatus.Optional
                    else -> PermStatus.Missing
                },
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
private fun LogSection(log: List<String>, onClear: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "logChevron")
    val canExpand = log.size > COLLAPSED_LOG_LINES

    Section("日志") {
        SectionCard {
            ListItem(
                modifier = if (canExpand) Modifier.toggleable(
                    value = expanded, role = Role.Button, onValueChange = { expanded = it },
                ) else Modifier,
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = null) },
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
            if (log.isNotEmpty()) {
                val lines = if (expanded) log.take(MAX_LOG_LINES) else log.take(COLLAPSED_LOG_LINES)
                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(cs.surfaceContainerHighest)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Terminal, contentDescription = null,
                            tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (expanded) "最近 ${lines.size} 条" else "最新",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
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
