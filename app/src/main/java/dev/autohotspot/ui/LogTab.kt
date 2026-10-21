package dev.autohotspot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一条日志：Prefs 写入的格式为「MM-dd HH:mm:ss  消息」 */
internal data class LogLine(val time: String?, val message: String)

private val LogPrefix = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(.*)$""")

internal fun parseLogLine(line: String): LogLine =
    LogPrefix.matchEntire(line)?.let { LogLine(it.groupValues[1], it.groupValues[2]) } ?: LogLine(null, line)

/** 「日志」栏：完整运行日志，最新在上 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogTab(
    log: List<String>,
    onClear: () -> Unit,
    listState: LazyListState,
    appBarState: TopAppBarState,
    modifier: Modifier = Modifier,
) {
    TabPage(
        "日志", listState, appBarState, modifier,
        itemSpacing = 2.dp,
        actions = {
            IconButton(onClick = onClear, enabled = log.isNotEmpty()) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = "清空")
            }
        },
    ) {
        if (log.isEmpty()) {
            item(key = "empty") { EmptyLog(Modifier.contentWidth()) }
        } else {
            item(key = "header") {
                Text(
                    "共 ${log.size} 条 · 最新在上",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .contentWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
            }
            // 同一条日志文本可能重复，因此用索引作 key
            itemsIndexed(log) { i, line ->
                LogEntry(parseLogLine(line), first = i == 0, last = i == log.lastIndex, Modifier.contentWidth())
            }
        }
    }
}

@Composable
private fun LogEntry(line: LogLine, first: Boolean, last: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val big = 16.dp
    val small = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (first) big else small,
        topEnd = if (first) big else small,
        bottomStart = if (last) big else small,
        bottomEnd = if (last) big else small,
    )
    SelectionContainer(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(cs.surfaceContainerLow)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (line.time != null) {
                Text(
                    line.time,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                line.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = cs.onSurface,
            )
        }
    }
}

@Composable
private fun EmptyLog(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(
            Icons.AutoMirrored.Rounded.ReceiptLong,
            container = cs.secondaryContainer,
            content = cs.onSecondaryContainer,
            size = 56.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text("暂无日志", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "自动打开/关闭热点、权限变化等操作会记录在这里",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
