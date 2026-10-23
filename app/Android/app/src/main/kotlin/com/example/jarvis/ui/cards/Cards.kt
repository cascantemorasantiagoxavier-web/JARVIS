package com.example.jarvis.ui.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.BusinessSearchResult
import com.example.jarvis.core.business.FieldStatus
import com.example.jarvis.core.business.FieldValue
import com.example.jarvis.core.business.Prospect
import com.example.jarvis.core.memory.MemoryItem
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.net.PageContent
import com.example.jarvis.core.net.WebSearchResult
import com.example.jarvis.core.notes.NoteSnippet
import com.example.jarvis.core.tasks.TaskItem
import com.example.jarvis.core.tasks.TaskStatus
import com.example.jarvis.core.tools.local.SpanishDates
import com.example.jarvis.core.weather.WeatherNow
import com.example.jarvis.ui.components.Corner
import com.example.jarvis.ui.components.FieldStatusTag
import com.example.jarvis.ui.components.HudChip
import com.example.jarvis.ui.components.HudPanel
import com.example.jarvis.ui.components.SectionLabel
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class BusinessAction { WEB, CALL, LOCATION, DIRECTIONS, SAVE, NOTE, COPY, VERIFY, DETAILS }

/** Acciones reales disparadas desde las tarjetas (implementadas en ui/CardActionHandler). */
interface CardActions {
    fun openUrl(url: String)
    fun business(action: BusinessAction, business: Business)
    fun showOnMap(ids: List<String>)
    fun loadMore(result: BusinessSearchResult)
    fun completeTask(task: TaskItem)
}

private val shortStamp = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

fun formatStamp(iso: String?): String = iso?.let {
    runCatching { shortStamp.format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrNull()
} ?: "no disponible"

@Composable
fun ResultCardView(card: ResultCard, actions: CardActions, modifier: Modifier = Modifier) {
    when (card) {
        is ResultCard.Businesses -> BusinessResultsCard(card.result, actions, modifier)
        is ResultCard.WebResults -> WebResultsCard(card.result, actions, modifier)
        is ResultCard.Page -> PageCard(card.page, actions, modifier)
        is ResultCard.Tasks -> TasksCard(card.title, card.tasks, actions, modifier)
        is ResultCard.Memories -> MemoriesCard(card.title, card.items, modifier)
        is ResultCard.Notes -> NotesCard(card.snippets, modifier)
        is ResultCard.Prospects -> ProspectsCard(card.title, card.groups, modifier)
        is ResultCard.Weather -> WeatherCard(card.weather, modifier)
        is ResultCard.Info -> InfoCard(card.title, card.lines, modifier)
    }
}

@Composable
fun BusinessResultsCard(r: BusinessSearchResult, actions: CardActions, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${r.category.label} · ${r.area?.name ?: "zona cercana"}", style = HudText.title, color = hud.bright)
                Text("${r.total} resultados · mostrando ${r.offset + 1}–${r.offset + r.results.size} · ${r.source?.name ?: "fuente"} · consultado ${formatStamp(r.retrievedAt)}", style = HudText.bodySmall, color = hud.textDim)
            }
            val withCoords = r.results.filter { it.mapPoint != null }
            if (withCoords.isNotEmpty()) HudChip("Ver en mapa", onClick = { actions.showOnMap(withCoords.map { it.id }) }, icon = Icons.Outlined.Map)
        }
        r.warnings.forEach { Text("⚠ $it", style = HudText.bodySmall, color = hud.warn) }
        r.results.forEach { BusinessCard(it, actions) }
        if (r.nextOffset != null) HudChip("Cargar más (${r.total - r.offset - r.results.size} restantes)", onClick = { actions.loadMore(r) })
        r.source?.license?.let { Text("Datos: $it", style = HudText.label.copy(fontSize = 9.sp), color = hud.textFaint) }
    }
}

@Composable
private fun FieldRow(label: String, field: FieldValue<String>, onClick: (() -> Unit)? = null) {
    val hud = LocalHud.current
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = HudText.bodySmall, color = hud.textFaint, modifier = Modifier.width(78.dp))
        Column(Modifier.weight(1f)) {
            val value = field.value
            if (value == null) {
                Text("No disponible", style = HudText.bodySmall, color = hud.textFaint)
            } else {
                Text(
                    value, style = HudText.body.copy(fontSize = 15.sp), color = if (onClick != null) hud.bright else hud.text,
                    textDecoration = if (onClick != null) TextDecoration.Underline else null,
                    modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                field.alternatives.forEach { Text("También: ${it.value} (${it.source})", style = HudText.bodySmall, color = hud.warn) }
                field.note?.let { Text(it, style = HudText.bodySmall, color = hud.textFaint) }
            }
        }
        if (field.value != null) FieldStatusTag(field.status, Modifier.padding(start = 6.dp, top = 3.dp))
    }
}

@Composable
fun BusinessCard(b: Business, actions: CardActions, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    HudPanel(modifier.fillMaxWidth(), cut = 10.dp, corners = setOf(Corner.TopLeft, Corner.BottomRight)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.displayName, style = HudText.strong, color = if (b.name.value != null) hud.text else hud.textFaint)
                Text(b.category.value ?: "Categoría no disponible", style = HudText.bodySmall, color = hud.textDim)
            }
        }
        Spacer(Modifier.height(4.dp))
        FieldRow("Dirección", b.address)
        FieldRow("Zona", FieldValue(b.locationLine, if (b.locationLine != null) b.province.status.takeIf { it != FieldStatus.UNAVAILABLE } ?: FieldStatus.FOUND else FieldStatus.UNAVAILABLE))
        FieldRow("Teléfono", b.phone, onClick = b.phone.value?.let { { actions.business(BusinessAction.CALL, b) } })
        FieldRow("Sitio web", b.website, onClick = b.website.value?.let { url -> { actions.openUrl(url) } })
        FieldRow("Correo", b.email)
        val coordsText = when {
            b.mapPoint == null -> "Sin coordenadas en la fuente"
            b.coordinates.status == FieldStatus.APPROXIMATE -> "Ubicación aproximada"
            else -> "Ubicación exacta"
        }
        Text("$coordsText · Fuente: ${b.sources.values.firstOrNull()?.name ?: "desconocida"} · ${formatStamp(b.retrievedAt)}", style = HudText.label.copy(fontSize = 9.sp), color = hud.textFaint)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip("Detalles", { actions.business(BusinessAction.DETAILS, b) }, icon = Icons.Outlined.Info)
            if (b.website.value != null) HudChip("Web", { actions.business(BusinessAction.WEB, b) }, icon = Icons.Outlined.Language)
            if (b.phone.value != null) HudChip("Llamar", { actions.business(BusinessAction.CALL, b) }, icon = Icons.Outlined.Phone)
            if (b.mapPoint != null) {
                HudChip("Ubicación", { actions.business(BusinessAction.LOCATION, b) }, icon = Icons.Outlined.Place)
                HudChip("Ruta", { actions.business(BusinessAction.DIRECTIONS, b) }, icon = Icons.Outlined.Directions)
            }
            HudChip("Guardar", { actions.business(BusinessAction.SAVE, b) }, icon = Icons.Outlined.BookmarkAdd)
            HudChip("Nota", { actions.business(BusinessAction.NOTE, b) }, icon = Icons.Outlined.NoteAdd)
            HudChip("Copiar", { actions.business(BusinessAction.COPY, b) }, icon = Icons.Outlined.ContentCopy)
            HudChip("Verificar", { actions.business(BusinessAction.VERIFY, b) }, icon = Icons.Outlined.VerifiedUser)
        }
    }
}

@Composable
fun WebResultsCard(r: WebSearchResult, actions: CardActions, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Búsqueda web: \"${r.query}\"", style = HudText.title, color = hud.bright)
        Text("Proveedor: ${r.providerLabel ?: "ninguno"} · consultado ${formatStamp(r.retrievedAt)}", style = HudText.bodySmall, color = hud.textDim)
        if (r.coverage == "encyclopedic") Text("Solo hay Wikipedia configurada: puede no contener información actual.", style = HudText.bodySmall, color = hud.warn)
        r.warnings.forEach { Text("⚠ $it", style = HudText.bodySmall, color = hud.warn) }
        r.results.forEach { item ->
            HudPanel(Modifier.fillMaxWidth(), cut = 8.dp, onClick = { actions.openUrl(item.url) }) {
                Text(item.title, style = HudText.strong, color = hud.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.url, style = HudText.bodySmall, color = hud.bright, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.snippet?.let { Text(it, style = HudText.bodySmall, color = hud.textDim, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                Text(
                    "Fuente: ${item.source ?: "no disponible"} · Publicado: ${item.publishedAt?.let(::formatStamp) ?: "no disponible"} · Consultado: ${formatStamp(item.retrievedAt)}",
                    style = HudText.label.copy(fontSize = 9.sp), color = hud.textFaint,
                )
            }
        }
    }
}

@Composable
fun PageCard(p: PageContent, actions: CardActions, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    var expanded by remember { mutableStateOf(false) }
    HudPanel(modifier.fillMaxWidth(), title = p.title ?: "Página", cut = 8.dp) {
        Text(p.finalUrl ?: p.url, style = HudText.bodySmall, color = hud.bright, modifier = Modifier.clickable { actions.openUrl(p.finalUrl ?: p.url) })
        Text("Contenido de terceros (no confiable) · consultado ${formatStamp(p.retrievedAt)} · publicado ${p.publishedAt ?: "no disponible"}", style = HudText.label.copy(fontSize = 9.sp), color = hud.warn)
        Spacer(Modifier.height(6.dp))
        Text(if (expanded) p.text else p.text.take(600) + if (p.text.length > 600) "…" else "", style = HudText.bodySmall, color = hud.textDim)
        if (p.text.length > 600) Text(if (expanded) "Ver menos" else "Ver más", style = HudText.bodySmall, color = hud.bright, modifier = Modifier.clickable { expanded = !expanded })
        if (p.contacts.phones.isNotEmpty() || p.contacts.emails.isNotEmpty()) {
            SectionLabel("Contactos publicados en la página")
            (p.contacts.phones + p.contacts.emails).forEach { Text(it, style = HudText.bodySmall, color = hud.text) }
        }
    }
}

@Composable
fun TasksCard(title: String, tasks: List<TaskItem>, actions: CardActions, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    val now = LocalDateTime.now()
    HudPanel(modifier.fillMaxWidth(), title = title, cut = 8.dp) {
        if (tasks.isEmpty()) Text("Sin tareas.", style = HudText.bodySmall, color = hud.textDim)
        tasks.take(12).forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (t.status == TaskStatus.DONE) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "Completar",
                    tint = hud.bright, modifier = Modifier.size(22.dp).clickable(enabled = t.status != TaskStatus.DONE) { actions.completeTask(t) },
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = HudText.body, color = if (t.status == TaskStatus.DONE) hud.textFaint else hud.text, textDecoration = if (t.status == TaskStatus.DONE) TextDecoration.LineThrough else null)
                    t.due?.let { Text(SpanishDates.dueLabel(it, t.hasTime, LocalDate.now()) + if (t.remind) " · aviso" else "", style = HudText.bodySmall, color = if (t.isOverdue(now)) hud.error else hud.textDim) }
                }
                Text(t.priority.label, style = HudText.label, color = hud.textFaint)
            }
        }
    }
}

@Composable
fun MemoriesCard(title: String, items: List<MemoryItem>, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    HudPanel(modifier.fillMaxWidth(), title = title, cut = 8.dp) {
        items.forEach { m ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.isPrivate) Icon(Icons.Outlined.Lock, "Privado", tint = hud.textFaint, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                Text("${m.kind.label}: ${m.content}", style = HudText.bodySmall, color = hud.text)
            }
        }
    }
}

@Composable
fun NotesCard(snippets: List<NoteSnippet>, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    HudPanel(modifier.fillMaxWidth(), title = "Notas de Obsidian", cut = 8.dp) {
        snippets.forEach { s ->
            Text(s.citation, style = HudText.bodySmall, color = hud.bright)
            Text(s.excerpt, style = HudText.bodySmall, color = hud.textDim, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
fun ProspectsCard(title: String, groups: Map<String, List<Prospect>>, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    HudPanel(modifier.fillMaxWidth(), title = title, cut = 8.dp) {
        groups.forEach { (group, list) ->
            SectionLabel("$group (${list.size})")
            list.take(15).forEach { p ->
                Text("• ${p.name} — ${p.status.label}" + (p.nextAction?.let { " · $it" } ?: ""), style = HudText.bodySmall, color = hud.text)
            }
        }
    }
}

@Composable
fun WeatherCard(w: WeatherNow, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    HudPanel(modifier.fillMaxWidth(), title = "Clima en ${w.place.label}", cut = 8.dp) {
        Text("${"%.1f".format(w.temperatureC)} °C · ${w.description}", style = HudText.strong, color = hud.text)
        Text("Observado: ${w.observedAt} · ${w.source}", style = HudText.label.copy(fontSize = 9.sp), color = hud.textFaint)
    }
}

@Composable
fun InfoCard(title: String, lines: List<String>, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    HudPanel(modifier.fillMaxWidth(), title = title, cut = 8.dp) {
        lines.forEach { Text(it, style = HudText.bodySmall, color = hud.text) }
    }
}
