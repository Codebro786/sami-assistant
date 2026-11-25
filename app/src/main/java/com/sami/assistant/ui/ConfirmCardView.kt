package com.sami.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sami.assistant.actions.ConfirmCard

/** Premium confirmation card for sensitive actions (payments, calls, messages). */
@Composable
fun ConfirmCardView(card: ConfirmCard, onConfirm: (String?) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var input by remember(card) { mutableStateOf(card.inputPrefill.orEmpty()) }
    var error by remember(card) { mutableStateOf<String?>(null) }
    var amount by remember(card) { mutableStateOf(card.editableAmount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.2f".format(it) }.orEmpty()) }
    val gold = Color(0xFFFFC66D)
    Column(
        modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF0151933), Color(0xF00B0D1E))))
            .border(1.dp, Brush.linearGradient(listOf(gold.copy(alpha = 0.6f), Color(0x669B4DFF))), RoundedCornerShape(28.dp))
            .clickable(enabled = false) {}.padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (card.editableAmount != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pay ₹", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                BasicTextField(amount, { v -> amount = v.filter { it.isDigit() || it == '.' }.take(9); error = null }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = TextStyle(color = gold, fontSize = 30.sp, fontWeight = FontWeight.SemiBold), cursorBrush = SolidColor(gold),
                    modifier = Modifier.width(IntrinsicSize.Min).widthIn(min = 40.dp))
                Text("  ✎", color = Color(0xFF7F89AD), fontSize = 16.sp)
            }
        } else Text(card.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        card.lines.forEach { Text(it, color = Color(0xFFB8C2E6), fontSize = 15.sp) }
        if (card.inputHint != null) {
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x33000000))
                .border(1.dp, if (error != null) Color(0xFFFF8FA3) else Color(0x33FFFFFF), RoundedCornerShape(14.dp)).padding(14.dp)) {
                if (input.isEmpty()) Text(card.inputHint, color = Color(0xFF7F89AD), fontSize = 15.sp)
                BasicTextField(input, { input = it.trim(); error = null }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(gold), modifier = Modifier.fillMaxWidth())
            }
            if (card.inputChips.isNotEmpty()) {
                Text("Their UPI app:", color = Color(0xFF7F89AD), fontSize = 12.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val labels = mapOf("ptyes" to "Paytm", "paytm" to "Paytm (old)", "ybl" to "PhonePe", "ibl" to "PhonePe·ibl", "axl" to "PhonePe·axl", "okaxis" to "GPay·axis")
                    card.inputChips.forEach { suffix ->
                        val selected = input.endsWith("@$suffix")
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) gold.copy(alpha = 0.3f) else Color(0x22FFFFFF))
                            .clickable { input = input.substringBefore('@') + "@" + suffix; error = null }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                            Text("${labels[suffix] ?: suffix}  @$suffix", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = Color(0xFFFF8FA3), fontSize = 12.sp) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF)).clickable(onClick = onCancel).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center) { Text("Cancel", color = Color.White, fontSize = 15.sp) }
            Box(Modifier.weight(1.4f).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(Color(0xFF4F6BFF), Color(0xFF9B4DFF))))
                .clickable {
                    val v = card.inputHint?.let { input }
                    val amt = amount.toDoubleOrNull()
                    val err = v?.let { card.inputValidator?.invoke(it) }
                        ?: if (card.editableAmount != null && (amt == null || amt <= 0 || amt > 100_000)) "Enter an amount between ₹1 and ₹1,00,000" else null
                    if (err != null) error = err
                    else onConfirm(if (card.editableAmount != null) "${v.orEmpty()}\n$amt" else v)
                }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Text((if (card.requireBiometric) "🔒 " else "") + card.confirmLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (card.voiceConfirm) Text("or say “yes” / “haan”", color = Color(0xFF7F89AD), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
