package com.example.jarvis.ui.memory

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.jarvis.AppContainer
import com.example.jarvis.core.memory.MemoryItem
import com.example.jarvis.core.memory.MemoryKind
import com.example.jarvis.core.memory.RememberResult
import com.example.jarvis.core.notes.NoteIndexStats
import com.example.jarvis.core.notes.NoteSnippet
import com.example.jarvis.data.repo.toModel
import com.example.jarvis.notes.VaultIndexer
import com.example.jarvis.ui.components.HudChip
import com.example.jarvis.ui.components.HudPanel
import com.example.jarvis.ui.components.ScreenScaffold
import com.example.jarvis.ui.components.SectionLabel
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(c: AppContainer, onBack: () -> Unit, notify: (String) -> Unit) {
    val hud = LocalHud.current
    val memories by remember { c.db.memories().observe().map { l -> l.map { it.toModel() } } }.collectAsState(initial = emptyList())
    val settings by c.settings.settings.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var deleting by remember { mutableStateOf<MemoryItem?>(null) }
    var stats by remember { mutableStateOf<NoteIndexStats?>(null) }
    var indexing by remember { mutableStateOf<String?>(null) }
    var testQuery by remember { mutableStateOf("") }
    var testResults by remember { mutableStateOf<List<NoteSnippet>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settings.vaultUri, indexing) { stats = runCatching { c.noteIndex.stats() }.getOrNull() }

    fun runIndex(uri: Uri) {
        indexing = "Indexando…"
        scope.launch {
            indexing = try {
                val r = c.vaultIndexer.index(uri) { n -> indexing = "Indexando… $n notas" }
                notify("Bóveda indexada: ${r.indexed} actualizadas, ${r.unchanged} sin cambios, ${r.skipped} omitidas, ${r.errors} errores.")
                null
            } catch (e: Exception) {
                notify("No se pudo indexar: ${e.message}")
                null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { c.vaultIndexer.persistAccess(uri) }
            scope.launch {
                c.settings.update { it.copy(vaultUri = uri.toString()) }
                VaultIndexer.schedulePeriodic(c.app)
                runIndex(uri)
            }
        }
    }

    ScreenScaffold("Memoria", onBack, actions = {
        IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "Nuevo recuerdo", tint = hud.bright) }
    }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                HudPanel(Modifier.fillMaxWidth().padding(top = 8.dp), title = "Privacidad") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(settings.shareMemoriesWithModel, { v -> scope.launch { c.settings.update { it.copy(shareMemoriesWithModel = v) } } })
                        Text("Enviar al modelo los recuerdos NO privados relevantes", style = HudText.bodySmall, color = hud.text)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(settings.autoNotesContext, { v -> scope.launch { c.settings.update { it.copy(autoNotesContext = v) } } })
                        Text("Añadir automáticamente fragmentos relevantes de Obsidian", style = HudText.bodySmall, color = hud.text)
                    }
                    Text("Los recuerdos marcados con candado y las notas privadas nunca salen del teléfono.", style = HudText.bodySmall, color = hud.textFaint)
                }
            }
            item {
                HudPanel(Modifier.fillMaxWidth(), title = "Bóveda de Obsidian") {
                    val uri = settings.vaultUri
                    if (uri == null) {
                        Text("No hay bóveda vinculada. Elige la carpeta de tu bóveda en el teléfono (sincronizada con Obsidian Sync, Syncthing u otra herramienta). JARVIS solo la lee.", style = HudText.bodySmall, color = hud.textDim)
                        Spacer(Modifier.height(6.dp))
                        HudChip("Vincular bóveda", { picker.launch(null) })
                    } else {
                        Text("Vinculada: ${Uri.parse(uri).lastPathSegment}", style = HudText.bodySmall, color = hud.text)
                        stats?.let { Text("${it.notes} notas · ${it.chunks} fragmentos · ${it.privateNotes} privadas (excluidas del modelo)", style = HudText.bodySmall, color = hud.textDim) }
                        indexing?.let { Text(it, style = HudText.label, color = hud.bright) }
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudChip("Reindexar", { runIndex(Uri.parse(uri)) }, enabled = indexing == null)
                            HudChip("Desvincular", {
                                scope.launch {
                                    c.vaultIndexer.releaseAccess(Uri.parse(uri))
                                    VaultIndexer.cancelPeriodic(c.app)
                                    c.noteIndex.clear()
                                    c.settings.update { it.copy(vaultUri = null) }
                                    notify("Bóveda desvinculada y su índice borrado.")
                                }
                            })
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(testQuery, { testQuery = it }, label = { Text("Probar búsqueda en notas") }, singleLine = true, modifier = Modifier.weight(1f))
                            HudChip("Buscar", { scope.launch { testResults = c.noteSearch.search(testQuery, 4, forModel = false) } })
                        }
                        testResults.forEach { s ->
                            Text(s.citation, style = HudText.bodySmall, color = hud.bright)
                            Text(s.excerpt, style = HudText.bodySmall, color = hud.textDim, maxLines = 4)
                        }
                    }
                }
            }
            item { SectionLabel("Recuerdos (${memories.size})") }
            if (memories.isEmpty()) item { Text("Aún no hay recuerdos. Di \"Recuerda que…\" o pulsa +.", style = HudText.body, color = hud.textDim) }
            items(memories, key = { it.id }) { m ->
                HudPanel(Modifier.fillMaxWidth(), cut = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.isPrivate) Icon(Icons.Outlined.Lock, "Privado", tint = hud.textDim, modifier = Modifier.size(16.dp))
                        Column(Modifier.weight(1f).padding(start = 6.dp)) {
                            Text(m.content, style = HudText.body, color = hud.text)
                            Text("${m.kind.label} · n.º ${m.id} · ${com.example.jarvis.ui.cards.formatStamp(m.updatedAt.toString())}", style = HudText.label, color = hud.textFaint)
                        }
                        IconButton(onClick = { editing = m }) { Icon(Icons.Outlined.Edit, "Editar", tint = hud.textDim) }
                        IconButton(onClick = { deleting = m }) { Icon(Icons.Outlined.Delete, "Eliminar", tint = hud.textDim) }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        MemoryDialog(editing, onDismiss = { adding = false; editing = null }) { text, kind, isPrivate ->
            val target = editing
            adding = false; editing = null
            scope.launch {
                if (target == null) {
                    when (val r = c.memory.remember(text, kind, isPrivate)) {
                        is RememberResult.Saved -> notify("Recuerdo guardado.")
                        is RememberResult.Duplicate -> notify("Ya existía un recuerdo igual; no se duplicó.")
                        is RememberResult.Updated -> notify("Preferencia actualizada (antes: \"${r.previous}\").")
                    }
                } else {
                    notify(if (c.memory.update(target.id, text) != null) "Recuerdo actualizado." else "No se pudo actualizar.")
                }
            }
        }
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Olvidar recuerdo") },
            text = { Text("JARVIS dejará de recordar: \"${m.content}\". ¿Continuar?") },
            confirmButton = { TextButton(onClick = { scope.launch { notify(if (c.memory.forget(m.id)) "Recuerdo eliminado." else "No se encontró.") }; deleting = null }) { Text("Olvidar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MemoryDialog(existing: MemoryItem?, onDismiss: () -> Unit, onSave: (String, MemoryKind, Boolean) -> Unit) {
    var text by remember { mutableStateOf(existing?.content.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: MemoryKind.FACT) }
    var isPrivate by remember { mutableStateOf(existing?.isPrivate ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nuevo recuerdo" else "Editar recuerdo") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text("Contenido") }, modifier = Modifier.fillMaxWidth())
                if (existing == null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MemoryKind.entries.forEach { k -> HudChip(k.label, { kind = k }, selected = kind == k) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(isPrivate, { isPrivate = it })
                        Text("Privado (nunca se envía al modelo)")
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = text.trim().length >= 3, onClick = { onSave(text.trim(), kind, isPrivate) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
