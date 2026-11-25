package com.sami.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sami.assistant.core.AssistantController
import com.sami.assistant.core.OrbState
import com.sami.assistant.ui.orb.SamiOrb

private val EdgeColors = listOf(Color(0xFF3EF2FF), Color(0xFF4F6BFF), Color(0xFF9B4DFF), Color(0xFFFF4FD8), Color(0xFFFFB84D), Color(0xFF3EF2FF))

/** Siri-style overlay: glowing screen edge, bottom scrim, compact orb and live captions. */
@Composable
fun AssistOverlay(assistant: AssistantController, onDismiss: () -> Unit, onOpenApp: () -> Unit, onConfirm: (com.sami.assistant.actions.ConfirmCard, String?) -> Unit) {
    val s by assistant.state.collectAsStateWithLifecycle()

    // Entrance: edge glow sweeps in, panel rises.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(450)) }

    val active = s.orb != OrbState.IDLE
    val glow by animateFloatAsState(
        when (s.orb) { OrbState.LISTENING -> 0.55f + s.level * 0.45f; OrbState.IDLE -> 0.25f; OrbState.ERROR -> 0.5f; else -> 0.75f },
        tween(180), label = "glow"
    )

    Box(Modifier.fillMaxSize().clickable(MutableInteractionSource(), null, onClick = onDismiss)) {
        EdgeGlow(intensity = glow * enter.value, error = s.orb == OrbState.ERROR)

        // Bottom scrim so captions stay readable over any app.
        // Soft glow behind the orb/captions (no hard dark band).
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.42f).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x5502030A), Color(0x9902030A)))))

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)
                .graphicsLayer { translationY = (1f - enter.value) * 120f; alpha = enter.value }
                .clickable(MutableInteractionSource(), null) {}, // taps on the panel don't dismiss
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            s.confirm?.let { card ->
                ConfirmCardView(card, onConfirm = { onConfirm(card, it) }, onCancel = { assistant.answerConfirm(false) }, modifier = Modifier.imePadding())
                Spacer(Modifier.height(12.dp))
            }
            AnimatedVisibility(s.confirm == null && s.response.isNotEmpty() && s.orb != OrbState.LISTENING, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                Text(
                    s.response, color = if (s.orb == OrbState.ERROR) Color(0xFFFF8FA3) else Color.White,
                    fontSize = 19.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 28.dp).clip(RoundedCornerShape(22.dp))
                        .background(Color(0x22FFFFFF)).padding(horizontal = 18.dp, vertical = 12.dp).animateContentSize()
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                s.transcript.ifEmpty { if (s.orb == OrbState.LISTENING || s.orb == OrbState.WAKE) "Listening…" else "" },
                color = if (s.transcript.isEmpty()) Color(0xFF9AA6CC) else Color(0xFFDDE4FF),
                fontSize = 22.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 28.dp).animateContentSize()
            )
            if (s.orb == OrbState.EXECUTING && s.actionLabel.isNotEmpty())
                Text(s.actionLabel, color = Color(0xFF7DF9FF), fontSize = 13.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp))

            Box(
                Modifier.size(150.dp).clickable(MutableInteractionSource(), null) { if (active) assistant.cancel() else assistant.startSession() },
                contentAlignment = Alignment.Center
            ) { SamiOrb(s.orb, s.level, Modifier.fillMaxSize()) }

            Text("Open Sami", color = Color(0x88FFFFFF), fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenApp).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

/** Animated, audio-reactive gradient that traces the screen's rounded edge. */
@Composable
private fun EdgeGlow(intensity: Float, error: Boolean) {
    val t by rememberInfiniteTransition(label = "edge").animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "rot")
    val colors = if (error) listOf(Color(0xFFFF4D6D), Color(0xFFFF4FD8), Color(0xFFFF4D6D)) else EdgeColors
    Canvas(Modifier.fillMaxSize()) {
        val radius = CornerRadius(48.dp.toPx())
        // Moving the sweep centre in a small circle makes the colours flow around the edge.
        val a = Math.toRadians(t.toDouble())
        val c = Offset(center.x + kotlin.math.cos(a).toFloat() * size.width * 0.25f, center.y + kotlin.math.sin(a).toFloat() * size.height * 0.25f)
        val brush = Brush.sweepGradient(colors, c)
        val inset = 2.dp.toPx()
        // Soft outer bloom (several widening strokes approximate a blur cheaply).
        for (i in 4 downTo 1) {
            val w = 6.dp.toPx() * i
            drawRoundRect(
                brush = brush,
                topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = radius, style = Stroke(w), alpha = intensity * 0.12f * (5 - i)
            )
        }
        drawRoundRect(brush = brush, topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = radius, style = Stroke(3.dp.toPx()), alpha = intensity)
    }
}
