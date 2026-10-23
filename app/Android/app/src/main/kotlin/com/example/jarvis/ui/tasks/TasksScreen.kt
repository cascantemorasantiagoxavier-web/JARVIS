package com.example.jarvis.ui.tasks

import android.Manifest
import android.os.Build
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.jarvis.AppContainer
import com.example.jarvis.core.tasks.TaskItem
import com.example.jarvis.core.tasks.TaskPriority
import com.example.jarvis.core.tasks.TaskStatus
import com.example.jarvis.core.tools.local.SpanishDates
import com.example.jarvis.data.repo.toModel
import com.example.jarvis.ui.CardActionHandler
import com.example.jarvis.ui.components.HudChip
import com.example.jarvis.ui.components.HudPanel
import com.example.jarvis.ui.components.ScreenScaffold
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun TasksScreen(c: AppContainer, actions: CardActionHandler, onBack: () -> Unit, notify: (String) -> Unit) {
    val hud = LocalHud.current
    val tasks by remember { c.db.tasks().observe().map { l -> l.map { it.toModel() } } }.collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("pending") }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TaskItem?>(null) }
    val scope = rememberCoroutineScope()
    val now = LocalDateTime.now()
    val today = LocalDate.now()
    val visible = tasks.filter {
        when (filter) {
            "pending" -> it.status == TaskStatus.PENDING
            "today" -> it.status == TaskStatus.PENDING && it.due?.toLocalDate() == today
            "overdue" -> it.isOverdue(now)
            "done" -> it.status == TaskStatus.DONE
            else -> true
        }
    }.sortedWith(compareBy<TaskItem>({ it.status }, { it.due ?: LocalDateTime.MAX }))

    ScreenScaffold("Tareas y recordatorios", onBack, actions = {
        IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "Nueva tarea", tint = hud.bright) }
    }) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("pending" to "Pendientes", "today" to "Hoy", "overdue" to "Vencidas", "done" to "Completadas", "all" to "Todas").forEach { (k, l) ->
                HudChip(l, { filter = k }, selected = filter == k)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (visible.isEmpty()) item { Text("No hay tareas en esta vista.", style = HudText.body, color = hud.textDim) }
            items(visible, key = { it.id }) { t ->
                HudPanel(Modifier.fillMaxWidth(), cut = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (t.status == TaskStatus.PENDING) scope.launch { actions.runTool("complete_task", mapOf("task_id" to t.id)).let { notify(it.message) } } }) {
                            Icon(if (t.status == TaskStatus.DONE) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "Completar", tint = hud.bright)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = HudText.strong, color = if (t.status == TaskStatus.DONE) hud.textFaint else hud.text, textDecoration = if (t.status == TaskStatus.DONE) TextDecoration.LineThrough else null)
                            if (t.description.isNotBlank()) Text(t.description, style = HudText.bodySmall, color = hud.textDim)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Prioridad ${t.priority.label}", style = HudText.label, color = hud.textFaint)
                                t.due?.let {
                                    Spacer(Modifier.width(8.dp))
                                    Text(SpanishDates.dueLabel(it, t.hasTime, today), style = HudText.bodySmall, color = if (t.isOverdue(now)) hud.error else hud.bright)
                                }
                                if (t.remind) Icon(Icons.Outlined.NotificationsActive, "Con aviso", tint = hud.textDim, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                            }
                        }
                        IconButton(onClick = { deleting = t }) { Icon(Icons.Outlined.Delete, "Eliminar", tint = hud.textDim) }
                    }
                }
            }
        }
    }

    if (creating) NewTaskDialog(onDismiss = { creating = false }) { title, description, priority, due, remind ->
        creating = false
        scope.launch {
            if (remind && Build.VERSION.SDK_INT >= 33) c.permissions.request(Manifest.permission.POST_NOTIFICATIONS)
            val args = mutableMapOf<String, Any?>("title" to title, "priority" to priority.name, "remind" to remind)
            if (description.isNotBlank()) args["description"] = description
            if (due.isNotBlank()) args["due"] = due
            notify(actions.runTool("create_task", args).message)
        }
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Eliminar tarea") },
            text = { Text("¿Eliminar \"${t.title}\"?") },
            confirmButton = { TextButton(onClick = { scope.launch { notify(if (c.tasks.delete(t.id)) "Tarea eliminada." else "No se pudo eliminar.") }; deleting = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun NewTaskDialog(onDismiss: () -> Unit, onCreate: (String, String, TaskPriority, String, Boolean) -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(TaskPriority.MEDIUM) }
    var remind by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva tarea") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(due, { due = it }, label = { Text("Fecha y hora (ej.: mañana a las 3 pm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TaskPriority.entries.forEach { p -> HudChip(p.label, { priority = p }, selected = priority == p) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remind, onCheckedChange = { remind = it })
                    Text("Avisarme (requiere fecha)")
                }
            }
        },
        confirmButton = { TextButton(enabled = title.trim().length >= 2, onClick = { onCreate(title.trim(), description, priority, due, remind) }) { Text("Crear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
