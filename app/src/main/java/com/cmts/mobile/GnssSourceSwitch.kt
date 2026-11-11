package com.cmts.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("定位源", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            SourceChoice("手机 GNSS", selected = source == GnssSource.PHONE, gold) { onSelect(GnssSource.PHONE) }
            SourceChoice("外接 UM982", selected = source == GnssSource.EXTERNAL, gold) { onSelect(GnssSource.EXTERNAL) }
        }
        if (status.isNotBlank()) {
            Text(
                status,
                color = if (error) MaterialTheme.colorScheme.error else gold,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SourceChoice(label: String, selected: Boolean, gold: Color, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) gold else Color.Unspecified,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
    )
}
