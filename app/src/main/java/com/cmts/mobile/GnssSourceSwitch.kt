package com.cmts.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun GnssSourceSwitch(onSelect: (GnssSource) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, prominent: Boolean = false) {
    val source = Live.gnssSource
    val status = Live.gnssStatus
    var expanded by remember { mutableStateOf(false) }
    val error = status.contains("拔出") || status.contains("拒绝") || status.contains("未检测") ||
        status.contains("失败") || status.contains("没有 USB") || status.contains("未发现")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = if (prominent) 80.dp else if (compact) 52.dp else 48.dp)
                    .semantics { contentDescription = "选择定位源${if (status.isNotBlank()) "，$status" else ""}" },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = if (prominent) 80.dp else if (compact) 52.dp else 48.dp), contentAlignment = Alignment.Center) {
                    val labelStyle = if (maxWidth < 144.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(if (source == GnssSource.PHONE) "手机 GNSS" else "外接 GNSS", style = labelStyle, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        Text(if (error) "异常 ▾" else "切换 ▾", style = MaterialTheme.typography.labelSmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (status.isNotBlank()) {
                    Text(status, Modifier.widthIn(max = 280.dp).padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                }
                listOf(GnssSource.PHONE to "手机 GNSS", GnssSource.EXTERNAL to "外接 GNSS").forEach { (choice, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { RadioButton(selected = source == choice, onClick = null) },
                        onClick = { expanded = false; onSelect(choice) },
                    )
                }
            }
        }
        if (!compact && status.isNotBlank()) {
            Text(
                status,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
