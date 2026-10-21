package dev.autohotspot.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 宽屏（如折叠屏内屏）上限制内容宽度，避免卡片被拉得过宽 */
internal val MaxContentWidth = 640.dp

/** 每个栏目里内容块的统一宽度约束 */
internal fun Modifier.contentWidth(): Modifier = widthIn(max = MaxContentWidth).fillMaxWidth()

// ───────────────────────────── 栏目骨架 ─────────────────────────────

/**
 * 单个栏目的页面骨架：可折叠的顶部栏 + 独立滚动的列表。
 * 列表状态与顶部栏状态由 [MainScreen] 持有，切换栏目后返回时保持滚动位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabPage(
    title: String,
    listState: LazyListState,
    appBarState: TopAppBarState,
    modifier: Modifier = Modifier,
    itemSpacing: Dp = 24.dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MediumTopAppBar(
                title = { Text(title) },
                actions = actions,
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
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            content = content,
        )
    }
}

// ───────────────────────────── 通用组件 ─────────────────────────────

@Composable
internal fun Section(
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
internal fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
internal fun transparentListItemColors() =
    ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
internal fun CardDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 列表项前的圆形图标底座 */
@Composable
internal fun IconBadge(
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
internal fun Chip(text: String, icon: ImageVector?, container: Color, content: Color) {
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
internal fun CheckSwitch(checked: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = null, // 整行可点，由外层 toggleable 处理
        thumbContent = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else null,
    )
}

// ───────────────────────────── 权限项 ─────────────────────────────

internal enum class PermStatus { Granted, Missing, Optional }

@Composable
internal fun PermissionItem(
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
