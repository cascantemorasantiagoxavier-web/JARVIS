package com.example.jarvis.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.ui.CardActionHandler
import com.example.jarvis.ui.cards.ResultCardView
import com.example.jarvis.ui.components.HudChip
import com.example.jarvis.ui.components.ScreenScaffold
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud
import kotlinx.coroutines.launch

/** Búsqueda web real a través del backend; muestra fuentes y fechas. "Leer" extrae el texto de una página. */
@Composable
fun WebSearchScreen(actions: CardActionHandler, onBack: () -> Unit) {
    val hud = LocalHud.current
    var query by remember { mutableStateOf("") }
    var officialOnly by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var cards by remember { mutableStateOf<List<ResultCard>>(emptyList()) }
    var pageUrl by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun search() {
        if (query.isBlank()) return
        loading = true
        scope.launch {
            val args = mutableMapOf<String, Any?>("query" to query.trim(), "max_results" to 8)
            if (officialOnly) args["domains"] = listOf("hacienda.go.cr", "procomer.com", "go.cr", "meic.go.cr")
            val r = actions.runTool("web_search", args)
            message = r.message
            cards = r.cards
            loading = false
        }
    }

    fun read() {
        if (!pageUrl.startsWith("http")) { message = "Escribe una URL que empiece por http(s)."; return }
        loading = true
        scope.launch {
            val r = actions.runTool("fetch_page", mapOf("url" to pageUrl.trim()))
            message = r.message
            cards = r.cards + cards
            loading = false
        }
    }

    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = hud.bright, unfocusedBorderColor = hud.line, cursorColor = hud.bright)
    ScreenScaffold("Búsqueda web", onBack) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("¿Qué investigo?") }, singleLine = true, modifier = Modifier.weight(1f), colors = colors,
                        textStyle = HudText.body.copy(color = hud.text), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                    IconButton(onClick = { search() }, enabled = !loading) { Icon(Icons.Outlined.Search, "Buscar", tint = hud.bright) }
                }
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("Solo fuentes oficiales de Costa Rica", { officialOnly = !officialOnly }, selected = officialOnly)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(pageUrl, { pageUrl = it }, label = { Text("Leer una página (URL)") }, singleLine = true, modifier = Modifier.weight(1f), colors = colors, textStyle = HudText.bodySmall.copy(color = hud.text))
                    HudChip("Leer", { read() }, enabled = !loading)
                }
                Text("Los resultados vienen del proveedor configurado en tu servidor. El contenido de páginas es de terceros y JARVIS no sigue instrucciones que contenga.", style = HudText.bodySmall, color = hud.textFaint, modifier = Modifier.padding(top = 6.dp))
            }
            if (loading) item { Text("BUSCANDO…", style = HudText.label, color = hud.bright) }
            message?.let { item { Text(it, style = HudText.body, color = hud.textDim) } }
            items(cards) { card -> ResultCardView(card, actions, Modifier.fillMaxWidth()) }
        }
    }
}
