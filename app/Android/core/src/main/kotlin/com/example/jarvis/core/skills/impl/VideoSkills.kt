package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.json.arr
import com.example.jarvis.core.json.bool
import com.example.jarvis.core.json.int
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.str
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.skills.Outcome
import com.example.jarvis.core.skills.Skill
import com.example.jarvis.core.skills.SkillContext
import com.example.jarvis.core.skills.SkillOutcome
import com.example.jarvis.core.skills.SkillSource
import com.example.jarvis.core.text.TextNormalizer
import com.example.jarvis.core.tools.local.NaturalDateParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class ClipRef(val number: Int, val curationId: String, val title: String? = null, val durationSec: Int? = null)

@Serializable
data class ClipPost(val clip: Int, val title: String, val privacy: String, val at: String, val scheduledFor: String? = null, val account: String? = null)

@Serializable
data class VideoProject(
    val projectId: String,
    val videoUrl: String,
    val createdAt: String,
    val stage: String = "PENDING",
    val clips: List<ClipRef> = emptyList(),
    val posts: List<ClipPost> = emptyList(),
)

fun SkillContext.videoProjects() = collection("video.projects", VideoProject.serializer())

/**
 * Clips con OpusClip y publicación en YouTube mediante la cuenta conectada en OpusClip.
 * Recortar gasta créditos (riesgo externo: confirmación); publicar es sensible (confirmación siempre,
 * privado por defecto). Recuerda los proyectos para que «sube el clip 2» funcione sin ids.
 */
class VideoClipsSkill : Skill {
    override val id = "video_clips"

    private val url = Regex("https?://\\S+")

    override fun parseText(text: String): JsonObject? {
        val t = TextNormalizer.fold(text)
        url.find(text)?.let { m -> if (Regex("\\b(clip|clips|recorta|corta)").containsMatchIn(t)) return jsonOf("action" to "create", "video_url" to m.value.trimEnd('.', ',', ')')) }
        Regex("\\b(sube|publica|programa)\\b.*\\bclip (\\d{1,2})\\b").find(t)?.let { m ->
            return jsonOf("action" to "publish", "clip" to m.groupValues[2].toInt())
        }
        if (Regex("\\b(estan|listos|estado)\\b.*\\bclips\\b|\\bmis clips\\b").containsMatchIn(t)) return jsonOf("action" to "status")
        return null
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        ctx.requireServer("Los clips de video")?.let { return it }
        return when (input.str("action")) {
            "create" -> create(input, ctx)
            "status" -> status(input, ctx)
            "publish" -> publish(input, ctx)
            "list" -> list(ctx)
            else -> Outcome.invalid("Acción desconocida.")
        }
    }

    private val source = SkillSource("OpusClip", "https://www.opus.pro", "servicio externo")
    private val rights = "Publica solo videos tuyos o con permiso: YouTube sanciona las infracciones de derechos de autor y puede cerrar el canal."

    private suspend fun create(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val videoUrl = input.str("video_url") ?: return Outcome.invalid("Falta el enlace público del video (YouTube, Drive, Vimeo…).")
        if (!Regex("^https?://\\S+$").matches(videoUrl)) return Outcome.invalid("El enlace no es válido: $videoUrl")
        ctx.progress("ENVIANDO A OPUSCLIP")
        val r = ctx.tool("clips_create", jsonOf("video_url" to videoUrl, "prompt" to input.str("prompt"), "language" to (input.str("language") ?: "auto"), "aspect" to (input.str("aspect") ?: "portrait")))
        if (!r.ok) return Outcome.fromTool(r, "Crear los clips")
        val d = r.data as JsonObject
        val projectId = d.str("project_id")!!
        ctx.videoProjects().save(projectId, VideoProject(projectId, videoUrl, ctx.nowIso(), d.str("stage") ?: "PENDING"))
        return Outcome.done(
            "Envié el video a OpusClip (proyecto $projectId). Suele tardar de 10 a 40 minutos; pregúntame «¿ya están mis clips?».",
            jsonOf("action" to "create", "project_id" to projectId, "stage" to d.str("stage")), listOf(source), listOf(rights),
        )
    }

    /** Proyecto pedido o, si no se indica, el más reciente. */
    private suspend fun project(input: JsonObject, ctx: SkillContext): VideoProject? {
        val col = ctx.videoProjects()
        input.str("project_id")?.let { id -> return col.get(id) ?: VideoProject(id, "", ctx.nowIso()) }
        return col.values().maxByOrNull { it.createdAt }
    }

    private suspend fun refresh(p: VideoProject, ctx: SkillContext): Pair<VideoProject, SkillOutcome?> {
        val r = ctx.tool("clips_status", jsonOf("project_id" to p.projectId))
        if (!r.ok) return p to Outcome.fromTool(r, "Consultar los clips")
        val d = r.data as JsonObject
        val clips = d.arr("clips")?.mapNotNull { e ->
            val o = e.jsonObject
            val cur = o.str("curation_id") ?: return@mapNotNull null
            ClipRef(o.int("number") ?: 0, cur, o.str("title"), o.int("duration_sec"))
        }.orEmpty()
        val updated = p.copy(stage = d.str("stage") ?: p.stage, clips = clips.ifEmpty { p.clips })
        ctx.videoProjects().save(p.projectId, updated)
        if (d.bool("failed") == true) return updated to Outcome.fail("clips_failed", "OpusClip no pudo procesar el video" + (d.str("error")?.let { ": $it" } ?: "."), "Revisa que el enlace sea público y que el video dure al menos unos minutos.")
        return updated to null
    }

    private fun clipLines(p: VideoProject) = p.clips.map { c -> "${c.number}. ${c.title ?: "(sin título)"}" + (c.durationSec?.let { " · ${it}s" } ?: "") }

    private suspend fun status(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val p = project(input, ctx) ?: return Outcome.fail("no_project", "No tengo proyectos de clips.", "Pásame un enlace: «haz clips de https://…».")
        val (u, err) = refresh(p, ctx)
        err?.let { return it }
        val clips = u.clips.map { mapOf("number" to it.number, "title" to it.title, "duration_sec" to it.durationSec) }
        if (u.clips.isEmpty()) {
            return Outcome.done("Los clips del proyecto ${u.projectId} siguen en proceso (etapa ${u.stage}). Vuelve a preguntarme en unos minutos.",
                jsonOf("action" to "status", "project_id" to u.projectId, "stage" to u.stage, "clips" to clips), listOf(source))
        }
        return Outcome.done(
            "Hay ${u.clips.size} clips listos: " + u.clips.take(5).joinToString("; ") { "${it.number}. ${it.title ?: "sin título"}" } + ". Di «sube el clip N a YouTube».",
            jsonOf("action" to "status", "project_id" to u.projectId, "stage" to u.stage, "clips" to clips), listOf(source),
            cards = listOf(ResultCard.Info("Clips de OpusClip", clipLines(u))),
        )
    }

    private suspend fun publish(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val p = project(input, ctx) ?: return Outcome.fail("no_project", "No tengo proyectos de clips.", "Primero pídeme hacer clips de un video.")
        val number = input.int("clip") ?: return Outcome.invalid("¿Qué clip subo? Dime el número: «sube el clip 2».")
        val current = if (p.clips.any { it.number == number }) p else refresh(p, ctx).let { (u, err) -> err?.let { return it }; u }
        val clip = current.clips.firstOrNull { it.number == number }
            ?: return Outcome.fail("no_clip", if (current.clips.isEmpty()) "Los clips aún no están listos (etapa ${current.stage})." else "No existe el clip $number; hay ${current.clips.size}.", "Pregúntame «¿ya están mis clips?».")
        val title = input.str("title") ?: clip.title ?: "Clip $number"
        val privacy = input.str("privacy") ?: "private"
        val publishAt = input.str("publish_at")?.let { raw ->
            val now = ctx.time.zonedNow()
            val due = NaturalDateParser.parse(raw, now) ?: return Outcome.invalid("No entendí la fecha «$raw». Por ejemplo: «mañana a las 6 pm».")
            val at = due.dateTime.atZone(now.zone)
            if (!at.isAfter(now)) return Outcome.invalid("La fecha de publicación debe ser futura.")
            at.toInstant().toString()
        }
        // Confirmación: la pide el ejecutor (clips_publish es sensible), mostrando título y privacidad.
        val r = ctx.tool("clips_publish", jsonOf("project_id" to current.projectId, "curation_id" to clip.curationId, "title" to title.take(100), "description" to input.str("description"), "privacy" to privacy, "publish_at" to publishAt))
        if (!r.ok) return Outcome.fromTool(r, "Publicar en YouTube")
        val d = r.data as JsonObject
        val post = ClipPost(number, title, privacy, ctx.nowIso(), publishAt, d.str("account"))
        ctx.videoProjects().save(current.projectId, current.copy(posts = current.posts + post))
        val warnings = mutableListOf(rights)
        if (privacy == "private") warnings += "Quedó privado: revísalo en YouTube Studio y publícalo cuando quieras."
        return Outcome.done(r.message, jsonOf("action" to "publish", "project_id" to current.projectId, "post" to d), listOf(source), warnings)
    }

    private suspend fun list(ctx: SkillContext): SkillOutcome {
        val all = ctx.videoProjects().values().sortedByDescending { it.createdAt }
        if (all.isEmpty()) return Outcome.done("No tienes proyectos de clips todavía.", jsonOf("action" to "list", "projects" to emptyList<Any>()))
        val rows = all.map { mapOf("project_id" to it.projectId, "video_url" to it.videoUrl, "stage" to it.stage, "clips" to it.clips.size, "posts" to it.posts.size, "created_at" to it.createdAt) }
        return Outcome.done("Tienes ${all.size} proyectos de clips; el más reciente: ${all.first().projectId} (${all.first().stage}).", jsonOf("action" to "list", "projects" to rows),
            cards = listOf(ResultCard.Info("Proyectos de clips", all.map { "${it.projectId} · ${it.stage} · ${it.clips.size} clips · ${it.posts.size} publicados" })))
    }
}

