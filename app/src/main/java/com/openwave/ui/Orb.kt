package com.openwave.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class DialTarget(val label: String, val icon: ImageVector, val deg: Float, val screen: Screen) {
    Search("Search", Icons.Rounded.Search, 180f, Screen.Search),
    Playlists("Playlists", Icons.Rounded.LibraryMusic, 225f, Screen.Playlists),
    Downloads("Downloads", Icons.Rounded.Download, 270f, Screen.Downloads),
}

/**
 * Bottom-right glass orb. Tap = onTap. Long-press = dial blooms around the orb;
 * keep the finger down and rotate toward a detent (haptic tick), release to open it.
 */
@Composable
fun OrbDial(modifier: Modifier, playing: Boolean, onTap: () -> Unit, onSelect: (Screen) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<DialTarget?>(null) }
    val progress by animateFloatAsState(if (open) 1f else 0f, spring(0.7f, 300f), label = "open")
    val knob by animateFloatAsState(sel?.deg ?: 225f, spring(0.6f, 400f), label = "knob")
    val scale by animateFloatAsState(
        if (open) 1.08f else if (pressed) 0.92f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "scale",
    )

    Box(
        modifier.size(72.dp)
            .drawBehind {
                if (progress > 0.01f) {
                    val c = center; val r = 130.dp.toPx() * progress; val p = progress
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(.22f * p), Color.White.copy(.07f * p)), c, r), r, c)
                    drawCircle(Color.White.copy(.35f * p), r, c, style = Stroke(1.dp.toPx()))
                    DialTarget.values().forEach { t ->
                        val a = Math.toRadians(t.deg.toDouble()); val d = Offset(cos(a).toFloat(), sin(a).toFloat())
                        drawLine(Color.White.copy(.5f * p), c + d * (r * .88f), c + d * (r * .96f), 2.dp.toPx(), StrokeCap.Round)
                    }
                    val k = Math.toRadians(knob.toDouble())
                    drawLine(Accent.copy(p), c, c + Offset(cos(k).toFloat(), sin(k).toFloat()) * (r * .5f), 3.dp.toPx(), StrokeCap.Round)
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(); pressed = true
                    var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { up = waitForUpOrCancellation(); true }
                    if (early != null) { pressed = false; if (up != null) onTap(); return@awaitEachGesture }
                    // long-press: open dial
                    open = true; sel = null
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val ctr = Offset(size.width / 2f, size.height / 2f)
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        val v = ch.position - ctr
                        if (v.getDistance() > 24.dp.toPx()) {
                            var ang = Math.toDegrees(atan2(v.y, v.x).toDouble()).toFloat(); if (ang < 0) ang += 360f
                            val near = DialTarget.values().minByOrNull { val d = abs(ang - it.deg); minOf(d, 360f - d) }
                            if (near != sel) { sel = near; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        }
                        ch.consume()
                    }
                    val pick = sel
                    open = false; pressed = false; sel = null
                    if (pick != null) onSelect(pick.screen)
                }
            },
    ) {
        DialTarget.values().forEach { t ->
            val a = Math.toRadians(t.deg.toDouble()); val on = sel == t
            val x = (cos(a) * 100).toFloat(); val y = (sin(a) * 100).toFloat()
            Column(
                Modifier.offset((x + 4).dp, (y + 4).dp).size(64.dp)
                    .graphicsLayer { alpha = progress; scaleX = progress; scaleY = progress }
                    .glass(CircleShape, if (on) 2.4f else 1f),
                Arrangement.Center, Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, t.label, tint = if (on) Accent else Marble, modifier = Modifier.size(22.dp))
                Text(t.label, fontSize = 10.sp, color = if (on) Accent else Marble, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }.glass(CircleShape, 1.7f),
            Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
                tint = Marble, modifier = Modifier.size(36.dp).graphicsLayer { rotationZ = (knob - 225f) * progress },
            )
        }
    }
}
