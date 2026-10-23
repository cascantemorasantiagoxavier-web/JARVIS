package com.example.jarvis.ui.business

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.jarvis.AppContainer
import com.example.jarvis.core.business.BusinessSearchResult
import com.example.jarvis.core.business.Prospect
import com.example.jarvis.core.business.ProspectStatus
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.data.repo.toModel
import com.example.jarvis.ui.CardActionHandler
import com.example.jarvis.ui.cards.BusinessResultsCard
import com.example.jarvis.ui.components.HudChip
import com.example.jarvis.ui.components.HudPanel
import com.example.jarvis.ui.components.ScreenScaffold
import com.example.jarvis.ui.components.SectionLabel
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun BusinessScreen(c: AppContainer, actions: CardActionHandler, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    ScreenScaffold("Negocios y prospectos", onBack) {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudChip("Buscar", onClick = { tab = 0 }, selected = tab == 0, icon = Icons.Outlined.Search)
            HudChip("Prospectos", onClick = { tab = 1 }, selected = tab == 1)
        }
        if (tab == 0) SearchTab(c, actions) else ProspectsTab(c, actions, onNavigate)
    }
}

@Composable
private fun SearchTab(c: AppContainer, actions: CardActionHandler) {
    val hud = LocalHud.current
    val settings by c.settings.settings.collectAsState()
    var category by remember { mutableStateOf("supermercados") }
    var location by remember { mutableStateOf(settings.defaultLocation ?: "San José") }
    var nearMe by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf<List<BusinessSearchResult>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val pageActions = remember(actions) {
        CardActionHandler(c, c.app, scope, notify = { message = it }, navigate = { actions.showOnMap(it.removePrefix("map?ids=").split(",")) },
            askNote = { actions.business(com.example.jarvis.ui.cards.BusinessAction.NOTE, it) },
            showDetails = { actions.business(com.example.jarvis.ui.cards.BusinessAction.DETAILS, it) },
            onMoreResults = { more -> pages = pages + more })
    }

    fun search() {
        loading = true; message = null
        scope.launch {
            val args = mutableMapOf<String, Any?>("category" to category.trim(), "limit" to 20)
            if (nearMe) args["near_me"] = true else args["location"] = location.trim()
            val r = actions.runTool("business_search", args)
            loading = false
            message = r.message
            pages = r.cards.filterIsInstance<ResultCard.Businesses>().map { it.result }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = hud.bright, unfocusedBorderColor = hud.line, cursorColor = hud.bright)
    LazyColumn(Modifier.fillMaxWidth().testTag("business_list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "Búsqueda real (OpenStreetMap)") {
                OutlinedTextField(category, { category = it }, label = { Text("Tipo de negocio") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors, textStyle = HudText.body.copy(color = hud.text))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(location, { location = it }, label = { Text("Zona (provincia, cantón o distrito)") }, singleLine = true, enabled = !nearMe, modifier = Modifier.fillMaxWidth(), colors = fieldColors, textStyle = HudText.body.copy(color = hud.text))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudChip("Cerca de mí", onClick = { nearMe = !nearMe }, selected = nearMe, icon = Icons.Outlined.MyLocation)
                    HudChip(if (loading) "Buscando…" else "Buscar", onClick = { if (!loading) search() }, icon = Icons.Outlined.Search, enabled = category.isNotBlank() && (nearMe || location.isNotBlank()))
                }
                Spacer(Modifier.height(6.dp))
                Text("Teléfonos, webs y correos se muestran solo si la fuente los publica. El mapa se abre únicamente si lo pides.", style = HudText.bodySmall, color = hud.textFaint)
            }
        }
        message?.let { item { Text(it, style = HudText.body, color = hud.textDim) } }
        items(pages, key = { "${it.retrievedAt}-${it.offset}" }) { page -> BusinessResultsCard(page, pageActions) }
    }
}

@Composable
private fun ProspectsTab(c: AppContainer, actions: CardActionHandler, onNavigate: (String) -> Unit) {
    val hud = LocalHud.current
    val prospects by remember { c.db.prospects().observe().map { list -> list.map { it.toModel() } } }.collectAsState(initial = emptyList())
    var groupBy by remember { mutableStateOf("status") }
    var editing by remember { mutableStateOf<Prospect?>(null) }
    var deleting by remember { mutableStateOf<Prospect?>(null) }
    val scope = rememberCoroutineScope()

    val groups = prospects.groupBy {
        when (groupBy) {
            "province" -> it.province ?: "Provincia no disponible"
            "canton" -> listOfNotNull(it.canton, it.province).joinToString(", ").ifBlank { "Cantón no disponible" }
            "category" -> it.category ?: "Sin categoría"
            else -> it.status.label
        }
    }.toSortedMap()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AGRUPAR", style = HudText.label, color = hud.textFaint)
                listOf("status" to "Estado", "province" to "Provincia", "canton" to "Cantón", "category" to "Categoría").forEach { (k, l) -> HudChip(l, { groupBy = k }, selected = groupBy == k) }
                val withCoords = prospects.filter { it.lat != null }
                if (withCoords.isNotEmpty()) HudChip("Ver en mapa", { onNavigate("map?prospects=1") }, icon = Icons.Outlined.Map)
            }
        }
        if (prospects.isEmpty()) item { Text("Aún no hay prospectos. Guarda negocios desde una búsqueda.", style = HudText.body, color = hud.textDim) }
        groups.forEach { (group, list) ->
            item { SectionLabel("$group (${list.size})") }
            items(list, key = { it.id }) { p ->
                HudPanel(Modifier.fillMaxWidth(), cut = 8.dp, onClick = { editing = p }) {
                    Text(p.name, style = HudText.strong, color = hud.text)
                    Text(listOfNotNull(p.category, listOfNotNull(p.district, p.canton, p.province).joinToString(", ").ifBlank { null }).joinToString(" · "), style = HudText.bodySmall, color = hud.textDim)
                    Text("Estado: ${p.status.label}" + (p.nextAction?.let { " · Próxima acción: $it" } ?: ""), style = HudText.bodySmall, color = hud.bright)
                    Text("Tel.: ${p.phone ?: "No disponible"} · Web: ${p.website ?: "No disponible"} · Correo: ${p.email ?: "No disponible"}", style = HudText.bodySmall, color = hud.textDim)
                    if (p.notes.isNotBlank()) Text("Notas: ${p.notes}", style = HudText.bodySmall, color = hud.text)
                    Text("Fuente: ${p.sourceName} · encontrado ${com.example.jarvis.ui.cards.formatStamp(p.foundAt.toString())}", style = HudText.label, color = hud.textFaint)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.snapshot()?.let { b ->
                            if (b.phone.value != null) HudChip("Llamar", { actions.business(com.example.jarvis.ui.cards.BusinessAction.CALL, b) }, icon = Icons.Outlined.Phone)
                            if (b.mapPoint != null) HudChip("Ruta", { actions.business(com.example.jarvis.ui.cards.BusinessAction.DIRECTIONS, b) }, icon = Icons.Outlined.Directions)
                        }
                        HudChip("Eliminar", { deleting = p }, icon = Icons.Outlined.Delete)
                    }
                }
            }
        }
    }

    editing?.let { p -> ProspectEditor(p, onDismiss = { editing = null }) { status, note, next ->
        scope.launch { c.prospects.update(p.id, status, note, next) }
        editing = null
    } }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Eliminar prospecto") },
            text = { Text("¿Eliminar \"${p.name}\" de tus prospectos? Esta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { scope.launch { c.db.prospects().let { dao -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dao.delete(p.id) } } }; deleting = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ProspectEditor(p: Prospect, onDismiss: () -> Unit, onSave: (ProspectStatus, String?, String?) -> Unit) {
    val hud = LocalHud.current
    var status by remember { mutableStateOf(p.status) }
    var note by remember { mutableStateOf("") }
    var next by remember { mutableStateOf(p.nextAction.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column {
                Text("Estado comercial", style = HudText.label, color = hud.textDim)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProspectStatus.entries.forEach { s -> HudChip(s.label, { status = s }, selected = status == s) }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Añadir nota") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(next, { next = it }, label = { Text("Próxima acción") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(status, note.ifBlank { null }, next.ifBlank { null }) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

