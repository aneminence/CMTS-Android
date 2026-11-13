package com.cmts.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.cos
import kotlin.math.sin

/** Orthographic views: vehicle nose is LEFT in the left-side view. Geometry is cached, not sampled. */
@Composable
internal fun VehicleIllustration(side: Boolean, reading: String, modifier: Modifier = Modifier) {
    val artwork = remember { VehicleArtwork() }
    val color = MaterialTheme.colorScheme
    val angle = reading.toFloatOrNull()
    val view = if (side) "车辆左视图，车头朝左" else "车辆正后视图"
    val description = if (angle == null) "$view，暂无姿态数据" else "$view，${if (side) "俯仰" else "侧倾"} $reading 度"
    Canvas(modifier.semantics { contentDescription = description }) {
        // The dial fits its viewport; a change of angle never changes the vehicle's size.
        val unit = minOf(size.width, size.height) / 116f
        if (unit <= 0f) return@Canvas
        val guide = color.outlineVariant
        withTransform({
            translate(center.x, center.y)
            scale(unit, unit, Offset.Zero)
        }) {
            // Restore the original circular instrument and stationary horizontal reference.
            drawCircle(guide, 53f, Offset.Zero, style = Stroke(.7f))
            for (tick in -90..90 step 15) rotate(tick.toFloat(), Offset.Zero) {
                for (sign in listOf(-1, 1)) {
                    drawLine(guide, Offset(sign * 48f, 0f), Offset(sign * 53f, 0f), 1f)
                }
            }
            drawLine(guide, Offset(-55f, 0f), Offset(55f, 0f), 1f)
            if (angle != null) {
                // Constant scales keep each view's entire rotation envelope inside radius 48.
                // Left: hypot(147, 67) * .28 < 46; rear: hypot(88, 67) * .41 < 46.
                val vehicleScale = if (side) .28f else .41f
                // Preserve the existing vehicle convention: +pitch = nose down, +roll = left up.
                withTransform({ scale(vehicleScale, vehicleScale, Offset.Zero) }) {
                    rotate(if (side) -angle else angle, Offset.Zero) {
                        if (side) drawLeftSide(artwork, color.primary, color.onSurface, color.surface)
                        else drawRear(artwork, color.primary, color.onSurface, color.surface)
                    }
                }
            }
        }
    }
}

private class VehicleArtwork {
    val sideBody = Path().apply {
        moveTo(-145f, 16f)
        cubicTo(-147f, 8f, -143f, -10f, -136f, -16f)
        cubicTo(-125f, -23f, -105f, -25f, -79f, -28f)
        lineTo(-47f, -52f)
        cubicTo(-37f, -59f, -25f, -62f, -9f, -62f)
        lineTo(38f, -62f)
        cubicTo(64f, -61f, 83f, -43f, 103f, -30f)
        cubicTo(114f, -28f, 131f, -26f, 139f, -19f)
        cubicTo(144f, -14f, 145f, 3f, 143f, 17f)
        lineTo(133f, 24f)
        lineTo(124f, 24f)
        cubicTo(121f, -15f, 75f, -15f, 71f, 24f)
        lineTo(-64f, 24f)
        cubicTo(-68f, -15f, -115f, -15f, -119f, 24f)
        lineTo(-135f, 23f)
        close()
    }
    val sideShoulder = Path().apply {
        moveTo(-139f, -10f)
        cubicTo(-103f, -16f, -87f, -18f, -68f, -18f)
        lineTo(102f, -19f)
        cubicTo(116f, -20f, 130f, -18f, 140f, -12f)
        lineTo(141f, -4f)
        cubicTo(65f, -12f, -49f, -10f, -142f, 0f)
        close()
    }
    val sideGlass = Path().apply {
        moveTo(-69f, -28f)
        lineTo(-41f, -49f)
        cubicTo(-33f, -55f, -22f, -56f, -9f, -56f)
        lineTo(36f, -56f)
        cubicTo(57f, -55f, 74f, -41f, 91f, -28f)
        close()
    }
    val glassReflection = Path().apply {
        moveTo(-53f, -30f); lineTo(-31f, -48f)
        cubicTo(-24f, -53f, -14f, -53f, -3f, -53f)
        lineTo(18f, -53f); lineTo(-13f, -30f); close()
    }
    val frontDoor = Path().apply {
        moveTo(-63f, -23f); lineTo(-65f, -1f); lineTo(-57f, 17f)
        lineTo(2f, 17f); lineTo(3f, -25f)
    }
    val rearDoor = Path().apply {
        moveTo(8f, -25f); lineTo(8f, 17f); lineTo(64f, 17f)
        cubicTo(68f, 5f, 70f, -10f, 72f, -24f)
    }
    val frontLamp = Path().apply {
        moveTo(-139f, -12f); lineTo(-117f, -16f); lineTo(-105f, -13f)
        lineTo(-116f, -7f); lineTo(-141f, -5f); close()
    }
    val rearBody = Path().apply {
        moveTo(-80f, 27f)
        cubicTo(-84f, 15f, -82f, -4f, -77f, -17f)
        lineTo(-65f, -51f)
        cubicTo(-57f, -63f, -41f, -67f, 0f, -67f)
        cubicTo(41f, -67f, 57f, -63f, 65f, -51f)
        lineTo(77f, -17f)
        cubicTo(82f, -4f, 84f, 15f, 80f, 27f)
        cubicTo(61f, 35f, -61f, 35f, -80f, 27f)
        close()
    }
    val rearGlass = Path().apply {
        moveTo(-55f, -48f)
        cubicTo(-47f, -55f, -31f, -58f, 0f, -58f)
        cubicTo(31f, -58f, 47f, -55f, 55f, -48f)
        lineTo(64f, -23f)
        quadraticTo(0f, -28f, -64f, -23f)
        close()
    }
    val rearHatch = Path().apply {
        moveTo(-68f, -19f); quadraticTo(0f, -24f, 68f, -19f)
        lineTo(64f, 17f); quadraticTo(0f, 23f, -64f, 17f); close()
    }
    val diffuser = Path().apply {
        moveTo(-73f, 22f); quadraticTo(0f, 29f, 73f, 22f)
        lineTo(66f, 31f); quadraticTo(0f, 37f, -66f, 31f); close()
    }
}

private fun DrawScope.drawLeftSide(art: VehicleArtwork, gold: Color, white: Color, background: Color) {
    val metal = Color(0xFF343A40)
    val trim = gold.copy(alpha = .65f)
    val glass = Color(0xFF101A23)
    drawPath(art.sideBody, metal)
    drawPath(art.sideShoulder, white.copy(alpha = .06f))
    drawPath(art.sideBody, trim, style = Stroke(1.2f))
    drawPath(art.sideGlass, glass)
    drawPath(art.glassReflection, Color(0xFF243744))
    drawPath(art.sideGlass, white.copy(alpha = .5f), style = Stroke(.8f))
    drawLine(background, Offset(2f, -56f), Offset(2f, -27f), 4.5f)
    drawLine(background, Offset(57f, -48f), Offset(63f, -27f), 3.5f)
    drawPath(art.frontDoor, Color(0xFF171D22), style = Stroke(1f))
    drawPath(art.rearDoor, Color(0xFF171D22), style = Stroke(1f))
    drawLine(white.copy(alpha = .23f), Offset(-57f, 18f), Offset(63f, 18f), 1f)
    for (x in listOf(-14f, 53f)) {
        drawLine(trim, Offset(x - 5f, -16f), Offset(x + 5f, -16f), 1.7f, StrokeCap.Round)
    }
    // Side mirror, front daytime running light, and rear lamp distinguish the vehicle's nose.
    drawRoundRect(metal, Offset(-61f, -31f), Size(14f, 7f), CornerRadius(3f, 3f))
    drawLine(trim, Offset(-60f, -31f), Offset(-49f, -31f), 1f, StrokeCap.Round)
    drawPath(art.frontLamp, white.copy(alpha = .9f))
    drawLine(gold, Offset(-138f, -5f), Offset(-118f, -7f), 1.6f, StrokeCap.Round)
    drawLine(Color(0xFFDF8A7A), Offset(129f, -13f), Offset(141f, -10f), 3.5f, StrokeCap.Round)
    drawLine(Color(0xFF13181E), Offset(-141f, 11f), Offset(-123f, 12f), 4f, StrokeCap.Round)
    drawLine(Color(0xFF13181E), Offset(128f, 15f), Offset(141f, 13f), 3f, StrokeCap.Round)
    drawWheel(Offset(-92f, 24f), 23f, gold, white)
    drawWheel(Offset(98f, 24f), 23f, gold, white)
}

private fun DrawScope.drawWheel(center: Offset, radius: Float, gold: Color, white: Color) {
    drawCircle(Color(0xFF0B0E12), radius, center)
    drawCircle(Color(0xFF555B61), radius - 1f, center, style = Stroke(1f))
    drawCircle(Color(0xFF272D34), radius * .69f, center)
    drawCircle(white.copy(alpha = .6f), radius * .68f, center, style = Stroke(1.4f))
    for (spoke in 0 until 5) {
        val theta = (spoke * 72.0 - 90.0) * Math.PI / 180.0
        val inner = center + Offset(cos(theta).toFloat(), sin(theta).toFloat()) * 4f
        val outer = center + Offset(cos(theta + .14).toFloat(), sin(theta + .14).toFloat()) * (radius * .59f)
        drawLine(white.copy(alpha = .55f), inner, outer, 2.4f, StrokeCap.Round)
    }
    drawCircle(gold.copy(alpha = .7f), 3.1f, center)
}

private fun DrawScope.drawRear(art: VehicleArtwork, gold: Color, white: Color, background: Color) {
    val trim = gold.copy(alpha = .65f)
    for (x in listOf(-75f, 55f)) {
        drawRoundRect(Color(0xFF0B0E12), Offset(x, 15f), Size(20f, 31f), CornerRadius(5f, 5f))
    }
    // Mirrors sit outside the cabin silhouette, at the correct height for an orthographic rear view.
    for (x in listOf(-88f, 73f)) {
        drawRoundRect(Color(0xFF343A40), Offset(x, -26f), Size(15f, 8f), CornerRadius(4f, 4f))
    }
    drawPath(art.rearBody, Color(0xFF343A40))
    drawPath(art.rearBody, trim, style = Stroke(1.2f))
    drawPath(art.rearGlass, Color(0xFF101A23))
    drawPath(art.rearGlass, white.copy(alpha = .5f), style = Stroke(.8f))
    drawLine(white.copy(alpha = .12f), Offset(-47f, -48f), Offset(38f, -48f), 2f, StrokeCap.Round)
    drawLine(gold.copy(alpha = .45f), Offset(-38f, -62f), Offset(38f, -62f), 1.2f)
    drawPath(art.rearHatch, Color(0xFF1A2127), style = Stroke(1f))
    drawLine(Color(0xFFDF8A7A), Offset(-71f, -12f), Offset(71f, -12f), 1.2f, StrokeCap.Round)
    for (sign in listOf(-1f, 1f)) {
        drawLine(Color(0xFFEC9D8C), Offset(sign * 71f, -14f), Offset(sign * 42f, -12f), 3.6f, StrokeCap.Round)
        drawLine(Color(0xFFDF8A7A), Offset(sign * 71f, -14f), Offset(sign * 70f, -5f), 2.5f, StrokeCap.Round)
    }
    drawCircle(trim, 3.3f, Offset(0f, -4f), style = Stroke(1.2f))
    drawRoundRect(background, Offset(-18f, 5f), Size(36f, 12f), CornerRadius(2f, 2f))
    drawRoundRect(white.copy(alpha = .35f), Offset(-18f, 5f), Size(36f, 12f), CornerRadius(2f, 2f), style = Stroke(.8f))
    drawPath(art.diffuser, Color(0xFF151A20))
    drawLine(white.copy(alpha = .23f), Offset(-69f, 22f), Offset(69f, 22f), 1f)
    for (x in listOf(-62f, 45f)) {
        drawRoundRect(white.copy(alpha = .5f), Offset(x, 27f), Size(17f, 5f), CornerRadius(2f, 2f), style = Stroke(1f))
    }
}
