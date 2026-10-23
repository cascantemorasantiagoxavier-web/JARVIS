package com.example.jarvis.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.jarvis.AppContainer
import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.FieldValue
import com.example.jarvis.core.model.UiEvent
import com.example.jarvis.ui.business.BusinessScreen
import com.example.jarvis.ui.cards.formatStamp
import com.example.jarvis.ui.chat.ChatScreen
import com.example.jarvis.ui.components.FieldStatusTag
import com.example.jarvis.ui.components.SectionLabel
import com.example.jarvis.ui.home.HomeScreen
import com.example.jarvis.ui.home.HomeViewModel
import com.example.jarvis.ui.map.MapScreen
import com.example.jarvis.ui.memory.MemoryScreen
import com.example.jarvis.ui.settings.SettingsScreen
import com.example.jarvis.ui.tasks.TasksScreen
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.JarvisTheme
import com.example.jarvis.ui.theme.LocalHud
import com.example.jarvis.ui.web.WebSearchScreen
import kotlinx.coroutines.launch

@Composable
fun JarvisApp(c: AppContainer, startRoute: String?) {
    val settings by c.settings.settings.collectAsState()
    JarvisTheme(settings.accent) {
        val hud = LocalHud.current
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val notify: (String) -> Unit = { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }
        var noteFor by remember { mutableStateOf<Business?>(null) }
        var detailsFor by remember { mutableStateOf<Business?>(null) }
        val actions = remember {
            CardActionHandler(c, c.app, scope, notify, navigate = { nav.navigate(it) }, askNote = { noteFor = it }, showDetails = { detailsFor = it })
        }
        val back: () -> Unit = { nav.popBackStack() }

        // El mapa solo se abre por petición explícita (herramienta show_on_map o botón)
        LaunchedEffect(Unit) {
            c.uiEvents.collect { e -> if (e is UiEvent.ShowMap) nav.navigate("map?ids=" + Uri.encode(e.businessIds.joinToString(","))) }
        }
        LaunchedEffect(Unit) { c.voice.notices.collect { notify(it) } }
        LaunchedEffect(startRoute) { if (!startRoute.isNullOrBlank() && startRoute != "home") nav.navigate(startRoute) }

        Box(Modifier.fillMaxSize().background(hud.background)) {
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    val vm: HomeViewModel = viewModel { HomeViewModel(c) }
                    HomeScreen(vm, onNavigate = { nav.navigate(it) }, onLaunchError = notify)
                }
                composable("chat") { ChatScreen(c, actions, back) }
                composable("web") { WebSearchScreen(actions, back) }
                composable("business") { BusinessScreen(c, actions, back, onNavigate = { nav.navigate(it) }) }
                composable(
                    "map?ids={ids}&prospects={prospects}",
                    arguments = listOf(
                        navArgument("ids") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("prospects") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    val explicit = entry.arguments?.getString("ids")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
                    val wantsProspects = entry.arguments?.getString("prospects") == "1"
                    val ids = if (explicit.isEmpty() && !wantsProspects) c.working.lastBusinesses().map { it.id } else explicit
                    MapScreen(c, ids, showProspects = wantsProspects || ids.isEmpty(), actions = actions, onBack = back)
                }
                composable("tasks") { TasksScreen(c, actions, back, notify) }
                composable("memory") { MemoryScreen(c, back, notify) }
                composable("settings") { SettingsScreen(c, back, notify) }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
        }

        // Confirmación explícita de acciones sensibles pedidas por la IA
        val pending by c.confirmations.pending.collectAsState()
        pending?.let { p ->
            AlertDialog(
                onDismissRequest = { c.confirmations.answer(false) },
                title = { Text("JARVIS necesita tu confirmación") },
                text = { Text("Acción: ${p.toolName}\n${p.summary}") },
                confirmButton = { TextButton(onClick = { c.confirmations.answer(true) }) { Text("Confirmar") } },
                dismissButton = { TextButton(onClick = { c.confirmations.answer(false) }) { Text("Cancelar") } },
            )
        }

        noteFor?.let { b ->
            var note by remember(b.id) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { noteFor = null },
                title = { Text("Nota para ${b.displayName}") },
                text = { OutlinedTextField(note, { note = it }, label = { Text("Nota (se guarda con el prospecto)") }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(enabled = note.isNotBlank(), onClick = { actions.saveWithNote(b, note.trim()); noteFor = null }) { Text("Guardar") } },
                dismissButton = { TextButton(onClick = { noteFor = null }) { Text("Cancelar") } },
            )
        }

        detailsFor?.let { b -> BusinessDetailsDialog(b, onOpen = actions::openUrl, onDismiss = { detailsFor = null }) }

        if (!settings.onboardingDone && !c.backendConfigured()) {
            AlertDialog(
                onDismissRequest = { scope.launch { c.settings.update { it.copy(onboardingDone = true) } } },
                title = { Text("Bienvenido, jefe") },
                text = {
                    Text(
                        "Sin configurar nada ya funcionan: hora, cálculos, conversiones, tareas con avisos, memoria, apps, clima y voz local.\n\n" +
                            "Para la voz GPT Realtime, el chat con IA, la búsqueda web y la de negocios, indica en Ajustes la URL y el token de tu servidor JARVIS (ver docs/SETUP.md).",
                    )
                },
                confirmButton = { TextButton(onClick = { scope.launch { c.settings.update { it.copy(onboardingDone = true) } }; nav.navigate("settings") }) { Text("Ir a Ajustes") } },
                dismissButton = { TextButton(onClick = { scope.launch { c.settings.update { it.copy(onboardingDone = true) } } }) { Text("Más tarde") } },
            )
        }
    }
}

/** Ficha completa: cada dato con su estado, alternativas y fuentes (con licencia y fecha de consulta). */
@Composable
private fun BusinessDetailsDialog(b: Business, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val hud = LocalHud.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(b.displayName) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                @Composable
                fun row(label: String, f: FieldValue<String>) {
                    Text(label, style = HudText.label, color = hud.textDim)
                    Text(f.value ?: "No disponible", style = HudText.body, color = if (f.value == null) hud.textFaint else hud.text)
                    if (f.value != null) FieldStatusTag(f.status)
                    f.alternatives.forEach { Text("Alternativa: ${it.value} (fuente: ${it.source})", style = HudText.bodySmall, color = hud.warn) }
                    f.note?.let { Text(it, style = HudText.bodySmall, color = hud.textFaint) }
                }
                row("Categoría", b.category)
                row("Marca", b.brand)
                row("Dirección", b.address)
                row("Provincia", b.province)
                row("Cantón", b.canton)
                row("Distrito", b.district)
                row("Teléfono", b.phone)
                row("Sitio web", b.website)
                row("Correo", b.email)
                row("Horario", b.openingHours)
                Text("Coordenadas", style = HudText.label, color = hud.textDim)
                Text(b.mapPoint?.let { "${it.lat}, ${it.lon}" } ?: "No disponible", style = HudText.body, color = hud.text)
                if (b.mapPoint != null) FieldStatusTag(b.coordinates.status)
                SectionLabel("Fuentes")
                b.sources.values.forEach { src ->
                    Text("${src.name} · consultado ${formatStamp(src.retrievedAt)}", style = HudText.bodySmall, color = hud.text)
                    src.license?.let { Text(it, style = HudText.bodySmall, color = hud.textFaint) }
                    src.url?.let { u -> TextButton(onClick = { onOpen(u) }) { Text("Abrir fuente") } }
                }
                if (b.mergedIds.isNotEmpty()) Text("Registros fusionados (mismo negocio en la fuente): ${b.mergedIds.joinToString()}", style = HudText.bodySmall, color = hud.textFaint)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
