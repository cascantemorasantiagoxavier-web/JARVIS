package com.example.jarvis.ui.skills

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.jarvis.AppContainer
import com.example.jarvis.core.skills.SkillAvailability
import com.example.jarvis.core.skills.SkillManifest
import com.example.jarvis.core.skills.SkillState
import com.example.jarvis.core.tools.RiskLevel
import com.example.jarvis.ui.components.HudChip
import com.example.jarvis.ui.components.HudPanel
import com.example.jarvis.ui.components.ScreenScaffold
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud

/**
 * Catálogo de habilidades con su estado REAL en este teléfono (según la configuración actual:
 * servidor, bóveda). Tocar un ejemplo lo envía al chat.
 */
@Composable
fun SkillsScreen(c: AppContainer, onBack: () -> Unit, onTry: (String) -> Unit) {
    val hud = LocalHud.current
    // Se recalcula al entrar: el estado depende del servidor y la bóveda configurados ahora.
    val entries = remember { c.skills.all().map { it to c.skills.availability(it.id, c.skillEnv) } }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val counts = entries.groupingBy { it.second.state }.eachCount()
    val shown = entries.filter { filter == null || it.second.state.name == filter }

    ScreenScaffold("Habilidades", onBack) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip("Todas ${entries.size}", onClick = { filter = null }, selected = filter == null)
            SkillState.entries.forEach { s ->
                val n = counts[s] ?: 0
                if (n > 0) HudChip("${stateLabel(s)} $n", onClick = { filter = s.name }, selected = filter == s.name)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().testTag("skills_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.first.id }) { (m, a) ->
                SkillRow(m, a, expanded == m.id, onToggle = { expanded = if (expanded == m.id) null else m.id }, onTry = onTry)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SkillRow(m: SkillManifest, a: SkillAvailability, open: Boolean, onToggle: () -> Unit, onTry: (String) -> Unit) {
    val hud = LocalHud.current
    HudPanel(Modifier.fillMaxWidth(), onClick = onToggle) {
        Row {
            Text(m.name, style = HudText.strong, color = hud.text, modifier = Modifier.weight(1f))
            Text(stateLabel(a.state), style = HudText.label, color = stateColor(a.state))
        }
        Text(m.shortDescription, style = HudText.bodySmall, color = hud.textDim)
        a.reasons.forEach { Text(it, style = HudText.bodySmall, color = hud.warn) }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text("Riesgo: ${riskLabel(m.risk)} · Internet: ${if (m.requiresInternet) "sí" else "no"} · Costo: ${m.cost}", style = HudText.bodySmall, color = hud.textDim)
            if (m.requiredPermissions.isNotEmpty()) Text("Permisos: " + m.requiredPermissions.joinToString(), style = HudText.bodySmall, color = hud.textDim)
            Text("Privacidad: ${m.privacy}", style = HudText.bodySmall, color = hud.textFaint)
            if (m.missing.isNotEmpty()) Text("Pendiente: " + m.missing.joinToString("; "), style = HudText.bodySmall, color = hud.warn)
            Spacer(Modifier.height(6.dp))
            Text("PRUEBA DECIR:", style = HudText.label, color = hud.bright)
            m.exampleUtterances.take(3).forEach { ex ->
                HudChip("«$ex»", onClick = { onTry(ex) }, enabled = a.runnable, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun stateLabel(s: SkillState) = when (s) {
    SkillState.IMPLEMENTADA -> "IMPLEMENTADA"
    SkillState.PARCIAL -> "PARCIAL"
    SkillState.REQUIERE_CONFIGURACION -> "REQUIERE CONFIG."
    SkillState.NO_IMPLEMENTADA -> "NO IMPLEMENTADA"
}

@Composable
private fun stateColor(s: SkillState): Color {
    val hud = LocalHud.current
    return when (s) {
        SkillState.IMPLEMENTADA -> hud.ok
        SkillState.PARCIAL -> hud.bright
        SkillState.REQUIERE_CONFIGURACION -> hud.warn
        SkillState.NO_IMPLEMENTADA -> hud.textFaint
    }
}

private fun riskLabel(r: RiskLevel) = when (r) {
    RiskLevel.READ -> "solo lectura"
    RiskLevel.LOCAL_WRITE -> "modifica datos del teléfono"
    RiskLevel.EXTERNAL -> "actúa fuera del teléfono (con confirmación)"
    RiskLevel.SENSITIVE -> "sensible (confirmación siempre)"
}
