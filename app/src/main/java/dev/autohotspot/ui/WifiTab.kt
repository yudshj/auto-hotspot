package dev.autohotspot.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 「Wi-Fi」栏：连接时需要关闭热点的 Wi-Fi 列表，以及添加入口 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WifiTab(
    state: MainUiState,
    actions: MainActions,
    listState: LazyListState,
    appBarState: TopAppBarState,
    modifier: Modifier = Modifier,
) {
    TabPage("Wi-Fi", listState, appBarState, modifier) {
        item(key = "any") { AnyWifiSection(state, actions, Modifier.contentWidth()) }
        item(key = "list") { WifiListSection(state, actions, Modifier.contentWidth()) }
        item(key = "add") { AddWifiSection(state, actions, Modifier.contentWidth()) }
    }
}

@Composable
private fun AnyWifiSection(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Section("匹配范围", modifier) {
        SectionCard {
            ListItem(
                modifier = Modifier.toggleable(
                    value = state.anyWifi, role = Role.Switch, onValueChange = actions.setAnyWifi,
                ),
                leadingContent = {
                    IconBadge(
                        Icons.Rounded.WifiFind,
                        container = if (state.anyWifi) cs.primary else cs.surfaceContainerHighest,
                        content = if (state.anyWifi) cs.onPrimary else cs.onSurfaceVariant,
                    )
                },
                headlineContent = { Text("任意 Wi-Fi") },
                supportingContent = {
                    Text(
                        if (state.anyWifi) "不按列表过滤：连上任何 Wi-Fi 都关闭热点，下方列表暂不生效"
                        else "开启后不按列表过滤，连上任何 Wi-Fi 都关闭热点"
                    )
                },
                trailingContent = { CheckSwitch(state.anyWifi) },
                colors = transparentListItemColors(),
            )
        }
    }
}

@Composable
private fun WifiListSection(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Section(
        "Wi-Fi 列表", modifier,
        trailing = when {
            state.anyWifi -> "未生效"
            state.ssids.isEmpty() -> null
            else -> "${state.ssids.size} 个"
        },
    ) {
        SectionCard(Modifier.animateContentSize().alpha(if (state.anyWifi) 0.6f else 1f)) {
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
        }
    }
}

@Composable
private fun AddWifiSection(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    var input by rememberSaveable { mutableStateOf("") }
    Section("添加", modifier) {
        SectionCard {
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
