package com.example.jarvis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.jarvis.AppContainer
import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.BusinessSearchResult
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.tasks.TaskItem
import com.example.jarvis.core.tools.ToolCall
import com.example.jarvis.core.tools.ToolContext
import com.example.jarvis.core.tools.ToolResult
import com.example.jarvis.ui.cards.BusinessAction
import com.example.jarvis.ui.cards.CardActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Los botones de las tarjetas ejecutan las MISMAS herramientas que el chat y la voz
 * (mismo ejecutor, validación y registro), no rutas paralelas.
 */
class CardActionHandler(
    private val c: AppContainer,
    private val context: Context,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val navigate: (String) -> Unit,
    private val askNote: (Business) -> Unit,
    private val showDetails: (Business) -> Unit,
    private val onMoreResults: (BusinessSearchResult) -> Unit = {},
) : CardActions {
    private val ids = AtomicLong(1)

    suspend fun runTool(name: String, args: Map<String, Any?>): ToolResult =
        c.executor.execute(ToolCall("ui-${ids.getAndIncrement()}", name, jsonOf(*args.toList().toTypedArray()).toString()), ToolContext(RequestSource.BUTTON, c.uiSink, c.working))

    private fun tool(name: String, args: Map<String, Any?>, after: (ToolResult) -> Unit = {}) {
        scope.launch {
            val r = runTool(name, args)
            notify(r.message)
            after(r)
        }
    }

    override fun openUrl(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return notify("Enlace no válido.")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { notify("No hay un navegador para abrir el enlace.") }
    }

    override fun business(action: BusinessAction, business: Business) {
        c.working.rememberBusiness(business)
        when (action) {
            BusinessAction.WEB -> business.website.value?.let(::openUrl)
            BusinessAction.CALL -> tool("call_phone", mapOf("business_id" to business.id))
            BusinessAction.LOCATION -> tool("open_location", mapOf("business_id" to business.id))
            BusinessAction.DIRECTIONS -> tool("get_directions", mapOf("business_id" to business.id))
            BusinessAction.SAVE -> tool("save_prospect", mapOf("business_id" to business.id))
            BusinessAction.NOTE -> askNote(business)
            BusinessAction.COPY -> {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText(business.displayName, business.toClipboardText()))
                notify("Datos de ${business.displayName} copiados (solo los que tienen fuente).")
            }
            BusinessAction.VERIFY -> tool("business_verify", mapOf("id" to business.id))
            BusinessAction.DETAILS -> showDetails(business)
        }
    }

    fun saveWithNote(business: Business, note: String) {
        c.working.rememberBusiness(business)
        tool("save_prospect", mapOf("business_id" to business.id, "note" to note)) { r ->
            if (r.ok && r.message.contains("ya estaba")) {
                // Ya existía: añadimos la nota al prospecto existente
                scope.launch {
                    c.prospects.list().firstOrNull { it.businessId == business.id }?.let { p ->
                        c.prospects.update(p.id, note = note)
                        notify("Nota añadida a ${p.name}.")
                    }
                }
            }
        }
    }

    override fun showOnMap(ids: List<String>) {
        navigate("map?ids=" + Uri.encode(ids.joinToString(",")))
    }

    override fun loadMore(result: BusinessSearchResult) {
        val next = result.nextOffset ?: return
        scope.launch {
            val args = mutableMapOf<String, Any?>("category" to (result.query.category ?: result.category.label), "offset" to next, "limit" to result.limit)
            result.query.location?.let { args["location"] = it }
            val r = runTool("business_search", args)
            val more = r.cards.filterIsInstance<ResultCard.Businesses>().firstOrNull()?.result
            if (more != null) onMoreResults(more) else notify(r.message)
        }
    }

    override fun completeTask(task: TaskItem) = tool("complete_task", mapOf("task_id" to task.id))
}
