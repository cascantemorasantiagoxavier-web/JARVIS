package com.example.jarvis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jarvis.AppContainer
import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.model.ChatEntry
import com.example.jarvis.core.model.JarvisRequest
import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.model.Role
import com.example.jarvis.ui.cards.CardActions
import com.example.jarvis.ui.cards.ResultCardView
import com.example.jarvis.ui.components.ChamferShape
import com.example.jarvis.ui.components.HudChip
import com.example.jarvis.ui.components.ScreenScaffold
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun ChatScreen(c: AppContainer, actions: CardActions, onBack: () -> Unit) {
    val hud = LocalHud.current
    val entries by c.orchestrator.entries.collectAsState()
    val activity by c.orchestrator.activity.collectAsState()
    val settings by c.settings.settings.collectAsState()
    var input by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(settings.defaultMode) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val busy = entries.lastOrNull()?.pending == true

    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1) }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        if (c.realtime.isActive) {
            c.realtime.sendText(text) // conversación de voz abierta: mismo contexto
        } else {
            val forced = runCatching { AssistantMode.valueOf(mode) }.getOrNull()
            if (forced == null) c.orchestrator.submit(text, RequestSource.TEXT)
            else submitWithMode(c, text, forced)
        }
    }

    ScreenScaffold("Chat con JARVIS", onBack, actions = {
        IconButton(onClick = { scope.launch { c.orchestrator.clearHistory() } }) { Icon(Icons.Outlined.DeleteSweep, "Borrar historial", tint = hud.textDim) }
    }) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ESTRATEGIA", style = HudText.label, color = hud.textFaint)
            listOf("AUTO", "FAST", "BALANCED", "DEEP").forEach { m -> HudChip(m, onClick = { mode = m }, selected = mode == m) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_list"), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (entries.isEmpty()) item {
                Text(
                    "Escribe o habla con JARVIS. Ejemplos: \"Busca supermercados en San José, Costa Rica\", \"Recuérdame llamar al proveedor mañana a las 3 pm\", \"¿Qué clima hace?\".",
                    style = HudText.body, color = hud.textDim, modifier = Modifier.padding(top = 24.dp),
                )
            }
            items(entries, key = { it.id }) { e -> ChatBubble(e, actions, if (e.pending) activity.label else null) }
        }
        Row(Modifier.fillMaxWidth().imePadding().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).testTag("chat_input"),
                placeholder = { Text("Escribe una orden…", color = hud.textFaint) },
                textStyle = HudText.body.copy(color = hud.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = hud.bright, unfocusedBorderColor = hud.line, cursorColor = hud.bright),
            )
            if (busy) {
                IconButton(onClick = { c.orchestrator.cancel() }) { Icon(Icons.Outlined.Cancel, "Cancelar", tint = hud.error) }
            }
            IconButton(onClick = { c.voice.toggleConversation() }) { Icon(Icons.Outlined.Mic, "Hablar", tint = hud.bright) }
            IconButton(onClick = { send() }, modifier = Modifier.testTag("chat_send")) { Icon(Icons.AutoMirrored.Outlined.Send, "Enviar", tint = hud.bright) }
        }
    }
}

/** Fuerza una estrategia concreta (FAST/BALANCED/DEEP) usando el mismo cerebro e historial. */
private fun submitWithMode(c: AppContainer, text: String, mode: AssistantMode) {
    c.orchestrator.submitRequest(JarvisRequest(text, RequestSource.TEXT, mode))
}

@Composable
private fun ChatBubble(e: ChatEntry, actions: CardActions, pendingLabel: String?) {
    val hud = LocalHud.current
    val isUser = e.role == Role.USER
    val isSystem = e.role == Role.SYSTEM
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        val shape = ChamferShape(8.dp)
        Box(
            Modifier.widthIn(max = 620.dp)
                .background(when { isUser -> hud.panelAlt; isSystem -> hud.panel.copy(alpha = 0.5f); else -> hud.panel }, shape)
                .border(1.dp, when { e.isError -> hud.error; isUser -> hud.line.copy(alpha = 0.4f); else -> hud.line }, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                val who = when { isUser -> "TÚ"; isSystem -> "SISTEMA"; else -> "JARVIS" } +
                    (if (e.source == RequestSource.VOICE) " · VOZ" else "") + " · " + timeFmt.format(e.at.atZone(ZoneId.systemDefault()))
                Text(who, style = HudText.label.copy(fontSize = 9.sp), color = if (isUser) hud.textFaint else hud.bright)
                Spacer(Modifier.height(2.dp))
                if (e.pending) {
                    Text("${pendingLabel ?: "PENSANDO"}…", style = HudText.label, color = hud.bright)
                } else if (e.text.isNotBlank()) {
                    Text(e.text, style = HudText.body, color = if (e.isError) hud.error else if (isSystem) hud.textDim else hud.text)
                }
                if (e.toolRuns.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        e.toolRuns.joinToString("  ") { (if (it.ok) "✓ " else "✗ ") + it.name + " " + "%.1f s".format(it.durationMs / 1000.0) },
                        style = HudText.label.copy(fontSize = 9.sp), color = hud.textFaint,
                    )
                }
                if (!isUser && e.mode != null) Text("estrategia ${e.mode} · ${if (e.usedModel) "modelo remoto" else "local"}", style = HudText.label.copy(fontSize = 8.sp), color = hud.textFaint)
            }
        }
        e.cards.forEach { card ->
            Spacer(Modifier.height(6.dp))
            ResultCardView(card, actions, Modifier.widthIn(max = 720.dp))
        }
    }
}

