package com.sami.assistant.ui.orb

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.sami.assistant.core.OrbState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val Cyan = Color(0xFF3EF2FF)
private val Blue = Color(0xFF4F6BFF)
private val Violet = Color(0xFF9B4DFF)
private val Magenta = Color(0xFFFF4FD8)
private val Red = Color(0xFFFF4D6D)
private val Amber = Color(0xFFFFB84D)

/**
 * Fully procedural assistant orb drawn on a Compose Canvas — no bitmaps.
 * One infinite clock drives everything; per-state parameters are spring-animated
 * so transitions between states morph rather than cut.
 */
@Composable
fun SamiOrb(state: OrbState, level: Float, modifier: Modifier = Modifier) {
    val clock = rememberInfiniteTransition(label = "orb")
    val t by clock.animateFloat(0f, (2 * PI).toFloat() * 10, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t")

    val lvl by animateFloatAsState(level, spring(stiffness = 400f), label = "lvl")
    val energy by animateFloatAsState(
        when (state) {
            OrbState.IDLE -> 0.15f; OrbState.WAKE -> 1f; OrbState.LISTENING -> 0.45f + lvl * 0.55f
            OrbState.THINKING -> 0.6f; OrbState.EXECUTING -> 0.7f; OrbState.RESPONDING -> 0.55f
            OrbState.ERROR -> 0.3f; OrbState.CONFIRMATION -> 0.35f
        }, spring(dampingRatio = 0.55f, stiffness = 120f), label = "energy"
    )
    val scale by animateFloatAsState(
        when (state) { OrbState.IDLE -> 0.82f; OrbState.WAKE -> 1.08f; OrbState.LISTENING -> 0.95f + lvl * 0.12f; else -> 0.92f },
        spring(dampingRatio = 0.5f, stiffness = 180f), label = "scale"
    )
    val primary by animateColorAsState(
        when (state) { OrbState.ERROR -> Red; OrbState.CONFIRMATION -> Amber; OrbState.THINKING -> Violet; else -> Blue }, tween(500), label = "c1"
    )
    val accent by animateColorAsState(
        when (state) { OrbState.ERROR -> Magenta; OrbState.CONFIRMATION -> Magenta; OrbState.RESPONDING -> Cyan; else -> Cyan }, tween(500), label = "c2"
    )
    val particles by animateFloatAsState(if (state == OrbState.THINKING || state == OrbState.EXECUTING) 1f else 0f, tween(400), label = "p")
    val wave by animateFloatAsState(if (state == OrbState.RESPONDING) 1f else 0f, tween(400), label = "w")

    Canvas(modifier) {
        val c = center
        val r = min(size.width, size.height) * 0.28f * scale
        val breathe = 1f + 0.03f * sin(t * 1.3f)

        // 1. Outer halo
        drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = 0.35f + energy * 0.25f), Color.Transparent), c, r * 2.4f), r * 2.4f, c)

        // 2. Morphing liquid blobs (additive) — amplitude follows energy / mic level
        val blobs = listOf(Triple(Cyan, 0f, 1.0f), Triple(Violet, 2.1f, 0.92f), Triple(Blue, 4.2f, 0.97f), Triple(accent, 1.0f, 0.85f))
        for ((i, b) in blobs.withIndex()) {
            val (col, phase, rs) = b
            drawBlob(c, r * rs * breathe, t * (0.6f + i * 0.17f) + phase, 0.05f + energy * 0.16f, Brush.radialGradient(
                listOf(col.copy(alpha = 0.85f), col.copy(alpha = 0.0f)), c + Offset(cos(t + phase) * r * 0.15f, sin(t * 0.8f + phase) * r * 0.15f), r * 1.15f
            ))
        }

        // 3. Glass core
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), accent.copy(alpha = 0.25f), Color.Transparent),
            c + Offset(-r * 0.25f, -r * 0.3f), r * 0.9f), r * 0.78f * breathe, c, blendMode = BlendMode.Screen)

        // 4. Rotating rim
        rotate(t * 18f, c) {
            drawCircle(Brush.sweepGradient(listOf(Cyan, Violet, Magenta.copy(alpha = 0.3f), Blue, Cyan), c),
                r * 1.02f * breathe, c, style = Stroke(width = r * (0.02f + energy * 0.03f)), alpha = 0.55f + energy * 0.4f)
        }

        // 5. Thinking / executing particles orbiting
        if (particles > 0.01f) {
            for (i in 0 until 18) {
                val a = t * (1.2f + (i % 3) * 0.35f) + i * (2 * PI.toFloat() / 18)
                val rr = r * (1.25f + 0.18f * sin(t * 2 + i))
                drawCircle(if (i % 2 == 0) Cyan else Magenta, r * 0.025f * (1 + (i % 3)), c + Offset(cos(a) * rr, sin(a) * rr), alpha = particles * 0.9f)
            }
        }

        // 6. Response waveform under the orb
        if (wave > 0.01f) {
            val y = c.y + r * 1.75f; val w = r * 2.2f
            for (k in 0..2) {
                val p = Path()
                for (x in 0..80) {
                    val fx = x / 80f
                    val env = sin(fx * PI.toFloat())
                    val yy = y + sin(fx * 14f + t * (5f + k) + k) * r * 0.16f * env * wave * (1f - k * 0.25f)
                    val xx = c.x - w / 2 + fx * w
                    if (x == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
                }
                drawPath(p, listOf(Cyan, Violet, Magenta)[k], alpha = 0.8f - k * 0.2f, style = Stroke(r * 0.018f, cap = StrokeCap.Round))
            }
        }
    }
}

private fun DrawScope.drawBlob(c: Offset, r: Float, t: Float, amp: Float, brush: Brush) {
    val path = Path(); val n = 64
    for (i in 0..n) {
        val a = i / n.toFloat() * 2 * PI.toFloat()
        val d = 1f + amp * (sin(a * 3 + t) * 0.6f + sin(a * 5 - t * 1.3f) * 0.3f + sin(a * 2 + t * 0.7f) * 0.4f)
        val x = c.x + cos(a) * r * d; val y = c.y + sin(a) * r * d
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, brush, blendMode = BlendMode.Plus)
}
