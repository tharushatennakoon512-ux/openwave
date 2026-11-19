package com.openwave.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.random.Random

val Ink = Color(0xFF07080B)
val Marble = Color(0xFFF4F1EA)
val Accent = Color(0xFFA9DCFF)

@Composable
fun OpenWaveTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink, background = Ink, surface = Ink, onSurface = Marble, onBackground = Marble),
    content = content,
)

/** Frosted glass: translucent fill, bright-edge border, soft elevation. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), strength: Float = 1f): Modifier = this
    .shadow(10.dp, shape, clip = false)
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(0.18f * strength), Color.White.copy(0.06f * strength))), shape)
    .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(0.5f), Color.White.copy(0.06f))), shape)

/** Springy press: scales down on touch, bounces back. Apply BEFORE glass(). */
fun Modifier.springPress(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.95f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    this.graphicsLayer { scaleX = s; scaleY = s }.clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
fun GlassIcon(icon: ImageVector, desc: String, onClick: () -> Unit, tint: Color = Marble, size: Dp = 44.dp) {
    Box(Modifier.size(size).springPress(onClick).glass(CircleShape), Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Fixed-seed marble veins: identical every frame and launch (anchored, no reshuffling). */
@Composable
fun MarbleBackground(artUrl: String?) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        if (artUrl != null) AsyncImage(artUrl, null, Modifier.fillMaxSize().blur(48.dp).alpha(0.35f), contentScale = ContentScale.Crop)
        Canvas(Modifier.fillMaxSize()) {
            val r = Random(7)
            drawRect(Brush.radialGradient(listOf(Color(0x22FFFFFF), Color.Transparent), Offset(size.width * .2f, size.height * .15f), size.width))
            repeat(7) {
                val p = Path().apply {
                    moveTo(r.nextFloat() * size.width, 0f)
                    cubicTo(r.nextFloat() * size.width, size.height * .3f, r.nextFloat() * size.width, size.height * .6f, r.nextFloat() * size.width, size.height)
                }
                drawPath(p, Color.White.copy(alpha = .05f), style = Stroke(1.2f + r.nextFloat() * 2f))
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(.85f)))))
    }
}
