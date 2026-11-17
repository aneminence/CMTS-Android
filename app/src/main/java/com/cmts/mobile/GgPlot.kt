package com.cmts.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Longitudinal / lateral linear acceleration trail for the attitude page. Does not replace the estimator. */
internal const val GG_TRAIL_NS = 5_000_000_000L
internal const val GG_RANGE_G = 1.0f

internal data class GgSample(val nanos: Long, val longG: Float, val latG: Float)

@Composable
internal fun GgCard(frame: ReplayFrame?, history: ReplayData?, modifier: Modifier = Modifier, compact: Boolean = false) {
 val trail = rememberGgTrail(frame, history)
 val latest = trail.lastOrNull()
 val reading = if (latest == null) "—" else "纵 ${"%+.2f".format(latest.longG)} g  侧 ${"%+.2f".format(latest.latG)} g"
 Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
  Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)) {
   if (compact) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
     Text("GG 图", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
     Text(reading, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
   } else {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
     Column(Modifier.weight(1f)) {
      Text("GG 图", style = MaterialTheme.typography.titleSmall)
      Text("线加速度 · 已去重力 · 拖影 5 s · ±1 g · 上加速 · 右右侧向", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
     Text(reading, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
   }
   GgDiagram(trail, Modifier.weight(1f).fillMaxWidth())
  }
 }
}

@Composable
internal fun rememberGgTrail(frame: ReplayFrame?, history: ReplayData?): List<GgSample> {
 if (history != null && frame != null) {
  val start = frame.nanos - GG_TRAIL_NS
  return history.frames.mapNotNull { item ->
   if (item.nanos < start || item.nanos > frame.nanos) return@mapNotNull null
   sampleFrom(item.samples, item.mountZero, item.nanos)
  }
 }
 val liveTrail = remember { mutableStateListOf<GgSample>() }
 val samples = Live.samples
 val zero = Live.mountZero
 val accel = samples["vehicle.accel"]
 LaunchedEffect(accel?.nanos, zero.enabled) {
  val point = sampleFrom(samples, zero, accel?.nanos ?: return@LaunchedEffect) ?: return@LaunchedEffect
  liveTrail.add(point)
  val cut = point.nanos - GG_TRAIL_NS
  while (liveTrail.isNotEmpty() && liveTrail.first().nanos < cut) liveTrail.removeAt(0)
 }
 return liveTrail.toList()
}

internal fun sampleFrom(samples: Map<String, Sample>, zero: MountZero, nanos: Long): GgSample? {
 val axes = displayVehicleAxes(zero, samples)
 val ax = axes.accel?.getOrNull(0)?.takeIf { it.isFinite() } ?: return null
 val ay = axes.accel?.getOrNull(1)?.takeIf { it.isFinite() } ?: return null
 return GgSample(nanos, (ax / STANDARD_GRAVITY).toFloat(), (ay / STANDARD_GRAVITY).toFloat())
}

@Composable
private fun GgDiagram(trail: List<GgSample>, modifier: Modifier) {
 Canvas(modifier) {
  val radius = min(size.width, size.height) / 2f - 8.dp.toPx()
  val c = Offset(size.width / 2f, size.height / 2f)
  val g = radius / GG_RANGE_G
  fun pos(longG: Float, latG: Float): Offset {
   // +longitudinal (forward accel) up; +lateral-to-the-right (+right = -vehicle Y).
   val x = c.x + (-latG).coerceIn(-GG_RANGE_G, GG_RANGE_G) * g
   val y = c.y - longG.coerceIn(-GG_RANGE_G, GG_RANGE_G) * g
   return Offset(x, y)
  }
  drawCircle(Color(0x22FFFFFF), radius, c)
  drawCircle(Color(0x66FFFFFF), radius, c, style = Stroke(1.5f))
  drawCircle(Color(0x44FFFFFF), radius * 0.5f, c, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
  drawLine(Color(0x55FFFFFF), Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1f)
  drawLine(Color(0x55FFFFFF), Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1f)
  val red = Color(0xFFE53935)
  if (trail.size >= 2) {
   val newest = trail.last().nanos
   for (i in 1 until trail.size) {
    val ageFrac = ((newest - trail[i].nanos).toFloat() / GG_TRAIL_NS).coerceIn(0f, 1f)
    val alpha = 0.15f + 0.80f * (1f - ageFrac)
    drawLine(red.copy(alpha = alpha), pos(trail[i - 1].longG, trail[i - 1].latG), pos(trail[i].longG, trail[i].latG), 3f)
   }
  }
  trail.lastOrNull()?.let { drawCircle(red, 7.dp.toPx(), pos(it.longG, it.latG)) }
 }
}
