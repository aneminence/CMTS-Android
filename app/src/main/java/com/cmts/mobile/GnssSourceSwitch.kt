package com.cmts.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun GnssSourceSwitch(onSelect: (GnssSource) -> Unit, modifier: Modifier = Modifier) {
    val source = Live.gnssSource
    val status = Live.gnssStatus
    val gold = Color(UsbSerialPorts.GOLD)
    val error = status.contains("拔出") || status.contains("拒绝") || status.contains("未检测") ||
        status.contains("失败") || status.contains("没有 USB") || status.contains("未发现")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Text("定位源", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = { onSelect(GnssSource.PHONE) }) {
                Text("手机 GNSS", color = if (source == GnssSource.PHONE) gold else Color.Unspecified)
            }
            TextButton(onClick = { onSelect(GnssSource.EXTERNAL) }) {
                Text("外接 UM982", color = if (source == GnssSource.EXTERNAL) gold else Color.Unspecified)
            }
        }
        if (status.isNotBlank()) {
            Text(
                status,
                color = if (error) MaterialTheme.colorScheme.error else gold,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
            )
        }
    }
}
