package com.sami.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sami.assistant.core.AssistantController
import com.sami.assistant.core.OrbState
import com.sami.assistant.core.Turn
import com.sami.assistant.ui.orb.SamiOrb

private val Bg = Color(0xFF02030A)
private val Glass = Color(0x14FFFFFF)
private val GlassBorder = Color(0x22FFFFFF)
private val Dim = Color(0xFF8A93B2)

@Composable
fun SamiRoot(vm: AssistantController, onListen: () -> Unit, onConfirm: (com.sami.assistant.actions.ConfirmCard, String?) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf("main") }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4F6BFF), background = Bg, surface = Bg)) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF070A1C), Bg, Color(0xFF0B0420))))
                .systemBarsPadding().imePadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                // Top bar
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sami", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    if (page == "main") {
                        Pill("History") { page = "history" }
                        Spacer(Modifier.width(8.dp))
                        Pill("Settings") { page = "settings" }
                    } else Pill("Close") { page = "main" }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { p ->
                        if (p == "history") HistoryList(s.history) else if (p == "settings") SettingsScreen(vm)
                        else if (s.confirm != null) {
                            // Confirmation gets the whole area (scrollable, keyboard-safe) instead of squeezing under the orb.
                            val card = s.confirm!!
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                StatusLine(s.orb)
                                Box(Modifier.size(120.dp)) { SamiOrb(s.orb, s.level, Modifier.fillMaxSize()) }
                                ConfirmCardView(card, { onConfirm(card, it) }, { vm.answerConfirm(false) })
                            }
                        } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            StatusLine(s.orb)
                            Box(Modifier.weight(1f).fillMaxWidth().clickable(MutableInteractionSource(), null, onClick = onListen), contentAlignment = Alignment.Center) {
                                SamiOrb(s.orb, s.level, Modifier.fillMaxSize())
                            }
                            AnimatedVisibility(s.actionLabel.isNotEmpty() && s.orb == OrbState.EXECUTING, enter = slideInVertically { it } + fadeIn(), exit = fadeOut()) {
                                Pill(s.actionLabel) {}
                            }
                            Text(
                                s.transcript.ifEmpty { if (s.orb == OrbState.IDLE) "Tap the orb and speak" else "" },
                                color = if (s.transcript.isEmpty()) Dim else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                            )
                            AnimatedVisibility(s.response.isNotEmpty() && s.orb != OrbState.LISTENING, enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically()) {
                                Text(
                                    s.response, color = if (s.orb == OrbState.ERROR) Color(0xFFFF8FA3) else Color(0xFFB9C6FF),
                                    fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }

                if (page == "main" && s.confirm == null) BottomBar(s.continuous, vm::toggleContinuous, vm::submitText)
            }
        }
    }
}

@Composable
private fun StatusLine(orb: OrbState) {
    val label = when (orb) {
        OrbState.IDLE -> "Ready"; OrbState.WAKE -> "Hi"; OrbState.LISTENING -> "● Listening"
        OrbState.THINKING -> "Thinking"; OrbState.EXECUTING -> "Working on it"; OrbState.RESPONDING -> "Speaking"
        OrbState.ERROR -> "Something went wrong"; OrbState.CONFIRMATION -> "Needs your confirmation"
    }
    val color = when (orb) { OrbState.LISTENING -> Color(0xFF3EF2FF); OrbState.ERROR -> Color(0xFFFF8FA3); else -> Dim }
    Text(label, color = color, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Glass).border(1.dp, GlassBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
    ) { Text(text, color = Color.White, fontSize = 13.sp) }
}

@Composable
private fun BottomBar(continuous: Boolean, onToggle: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Glass).border(1.dp, GlassBorder, RoundedCornerShape(28.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Text("Type a command…", color = Dim, fontSize = 15.sp)
                BasicTextField(
                    text, { text = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color(0xFF3EF2FF)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { onSubmit(text); text = "" } }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Continuous conversation", color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(continuous, { onToggle() }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF4F6BFF)))
        }
    }
}

@Composable
private fun HistoryList(history: List<Turn>) {
    val listState = rememberLazyListState()
    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollToItem(history.lastIndex) }
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No conversation yet", color = Dim) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(history) { turn ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (turn.fromUser) Arrangement.End else Arrangement.Start) {
                Column(
                    Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (turn.fromUser) Color(0x334F6BFF) else Glass).padding(12.dp)
                ) {
                    Text(turn.text, color = Color.White, fontSize = 15.sp)
                    turn.status?.let { Text(it, color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}
