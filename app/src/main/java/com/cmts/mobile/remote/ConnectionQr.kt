package com.cmts.mobile.remote

import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.json.JSONObject

@Composable
fun ConnectionQrButton(identity: RemoteIdentityStore) {
    var shown by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { host = LocalIpv4.resolvePreferred(); shown = true }) { Text("连接二维码") }
    if (shown) {
        val address = host
        val matrix = remember(address) {
            address?.let {
                val data = JSONObject().put("discoveryVersion", 1).put("deviceId", identity.deviceId)
                    .put("deviceLabel", identity.deviceLabel).put("deviceModel", identity.deviceModel).put("host", it).put("port", 8787)
                    .put("path", "/cmts/v1").put("proto", 1).put("deviceType", "cmts-android").toString()
                runCatching { MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, 256, 256) }.getOrNull()
            }
        }
        AlertDialog(onDismissRequest = { shown = false }, title = { Text("连接信息（不含配对码）") },
            text = {
                if (matrix == null) Text("无可用局域网地址，或二维码生成失败")
                else Canvas(Modifier.size(256.dp)) {
                    drawRect(Color.White)
                    val cell = size.width / matrix.width
                    for (y in 0 until matrix.height) for (x in 0 until matrix.width)
                        if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
                }
            }, confirmButton = { TextButton(onClick = { shown = false }) { Text("关闭") } })
    }
}
