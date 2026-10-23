package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.device.MediaFile
import com.example.jarvis.core.device.MediaResult
import com.example.jarvis.core.device.Pixels
import com.example.jarvis.core.json.bool
import com.example.jarvis.core.json.double
import com.example.jarvis.core.json.int
import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.obj
import com.example.jarvis.core.json.str
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.skills.Outcome
import com.example.jarvis.core.skills.Skill
import com.example.jarvis.core.skills.SkillContext
import com.example.jarvis.core.skills.SkillOutcome
import com.example.jarvis.core.skills.SkillSource
import com.example.jarvis.core.skills.SkillStatus
import com.example.jarvis.core.text.TextNormalizer
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.util.Base64

/** Lectura de códigos de barras y QR en el teléfono con ZXing (sin red). */
object BarcodeReader {
    data class Barcode(val text: String, val format: String)

    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF, BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX),
    )

    fun read(p: Pixels): Barcode? = try {
        val r = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(p.width, p.height, p.argb))), hints)
        Barcode(r.text, r.barcodeFormat.name)
    } catch (e: NotFoundException) {
        null
    } catch (e: ReaderException) {
        null
    }

    /** vCard o MECARD (QR de tarjetas de presentación) → campos. */
    fun contactFromQr(text: String): Map<String, String>? {
        val t = text.trim()
        if (t.startsWith("BEGIN:VCARD", true)) {
            val f = mutableMapOf<String, String>()
            t.lines().forEach { line ->
                val key = line.substringBefore(':').substringBefore(';').uppercase(); val v = line.substringAfter(':', "").trim()
                when (key) {
                    "FN" -> f["name"] = v
                    "N" -> if ("name" !in f) f["name"] = v.split(';').filter { it.isNotBlank() }.reversed().joinToString(" ")
                    "ORG" -> f["company"] = v.replace(';', ' ').trim()
                    "TITLE" -> f["title"] = v
                    "TEL" -> f.putIfAbsent("phone", v)
                    "EMAIL" -> f.putIfAbsent("email", v)
                    "URL" -> f.putIfAbsent("website", v)
                    "ADR" -> f.putIfAbsent("address", v.split(';').filter { it.isNotBlank() }.joinToString(", "))
                }
            }
            return f.takeIf { it.isNotEmpty() }
        }
        if (t.startsWith("MECARD:", true)) {
            val f = mutableMapOf<String, String>()
            t.removePrefix("MECARD:").split(';').forEach { part ->
                val k = part.substringBefore(':').uppercase(); val v = part.substringAfter(':', "")
                when (k) { "N" -> f["name"] = v.split(',').reversed().joinToString(" ").trim(); "TEL" -> f.putIfAbsent("phone", v); "EMAIL" -> f.putIfAbsent("email", v); "URL" -> f.putIfAbsent("website", v); "ORG" -> f["company"] = v; "ADR" -> f["address"] = v }
            }
            return f.takeIf { it.isNotEmpty() }
        }
        return null
    }
}

/** Obtiene una imagen de la cámara o de un archivo elegido por el usuario. */
internal suspend fun SkillContext.image(source: String?): Pair<MediaFile?, SkillOutcome?> {
    val r = if (source == "file") services.media.pickDocument(listOf("image/jpeg", "image/png", "image/webp")) else services.media.capturePhoto()
    return when (r) {
        is MediaResult.Ok -> r.file to null
        MediaResult.Cancelled -> null to SkillOutcome(SkillStatus.CANCELLED, "No se tomó ni eligió ninguna imagen.", errorCode = "cancelled")
        is MediaResult.Unavailable -> null to Outcome.fail("media_unavailable", r.reason, "Abre JARVIS en pantalla y vuelve a intentarlo.")
    }
}

// ============================================================ voice_memo_inbox

class VoiceMemoInboxSkill : Skill {
    override val id = "voice_memo_inbox"

    fun classify(text: String, ctx: SkillContext? = null): String {
        val f = TextNormalizer.fold(text)
        return when {
            Regex("\\b(gaste|pague|compre|recibi|cobre|me pagaron)\\b").containsMatchIn(f) && Money.parse(text, "CRC") != null -> "expense"
            Regex("\\b(recuerda que|recordar que|prefiero|me gusta|no me gusta|mi [a-z]+ es)\\b").containsMatchIn(f) -> "memory"
            Regex("\\b(tengo que|hay que|debo|llamar|comprar|enviar|pagar|recordarme|recuerdame|no olvidar|agendar|revisar|preparar|cotizar)\\b").containsMatchIn(f) -> "task"
            else -> "idea"
        }
    }

    override fun parseText(text: String): JsonObject? {
        val body = SkillText.afterCue(text, listOf("nota de voz", "anota en la bandeja", "guarda esta idea", "apunta esta idea", "a la bandeja")) ?: return null
        return jsonOf("action" to "capture_text", "text" to body)
    }

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val col = ctx.inbox()
        val src = listOf(SkillSource("Bandeja de notas de voz (teléfono)", kind = "local"))
        fun view(id: String, i: InboxItem) = mapOf("id" to id, "text" to i.text, "source" to i.source, "status" to i.status, "suggestion" to i.suggestion, "created_at" to i.createdAt, "converted_to" to i.convertedTo)
        return when (input.str("action")) {
            "capture_text" -> {
                val text = input.str("text")?.trim()?.takeIf { it.length >= 2 } ?: return Outcome.invalid("Falta el texto de la nota.")
                val id = col.nextId()
                val item = InboxItem(text, input.str("source") ?: "voz", ctx.nowIso(), suggestion = classify(text))
                col.save(id, item)
                Outcome.done("Guardé la nota en la bandeja (id $id). Parece ${label(item.suggestion)}; dime «conviértela» si quieres.", jsonOf("action" to "capture_text", "item" to view(id, item)), src)
            }
            "record" -> {
                ctx.requireServer("Transcribir notas de voz")?.let { return it }
                val audio = when (val r = ctx.services.media.recordAudio((input.int("max_seconds") ?: 60).coerceIn(5, 300))) {
                    is MediaResult.Ok -> r.file
                    MediaResult.Cancelled -> return SkillOutcome(SkillStatus.CANCELLED, "Grabación cancelada.", errorCode = "cancelled")
                    is MediaResult.Unavailable -> return Outcome.fail("media_unavailable", r.reason, "Abre JARVIS en pantalla y concede el micrófono.")
                }
                val t = ctx.tool("transcribe_audio", jsonOf("audio_base64" to Base64.getEncoder().encodeToString(audio.bytes), "mime_type" to audio.mimeType, "language" to "es"))
                if (!t.ok) return Outcome.fromTool(t, "La transcripción")
                val text = (t.data as JsonObject).str("text")?.trim().orEmpty()
                if (text.isBlank()) return Outcome.fail("empty_transcript", "No se entendió nada en la grabación.", "Graba de nuevo más cerca del micrófono.")
                val id = col.nextId()
                val item = InboxItem(text, "grabación", ctx.nowIso(), suggestion = classify(text))
                col.save(id, item)
                Outcome.done("Transcribí y guardé: «${SkillText.truncate(text, 120)}» (id $id).", jsonOf("action" to "record", "item" to view(id, item)), src, warnings = listOf("Transcripción automática: revísala."))
            }
            "list", "process" -> {
                val open = col.all().filter { it.second.status == "open" }
                Outcome.done(if (open.isEmpty()) "La bandeja está vacía." else "${open.size} notas sin procesar: " + open.groupingBy { label(it.second.suggestion) }.eachCount().entries.joinToString { "${it.value} ${it.key}" } + ".",
                    jsonOf("action" to input.str("action"), "items" to open.map { view(it.first, it.second) }), src,
                    cards = listOf(ResultCard.Info("Bandeja de notas", open.map { (id, i) -> "#$id [${label(i.suggestion)}] ${SkillText.truncate(i.text, 90)}" })))
            }
            "convert" -> {
                val id = input.str("item_id") ?: return Outcome.invalid("Falta item_id.")
                val item = col.get(id) ?: return Outcome.fail("not_found", "No existe la nota $id.", "Pide la bandeja.")
                if (item.status != "open") return Outcome.fail("already_processed", "La nota $id ya fue procesada (${item.convertedTo ?: item.status}).", "No se convierte dos veces para no duplicar.")
                val target = input.str("to") ?: item.suggestion ?: "task"
                val result: String = when (target) {
                    "task" -> {
                        val due = Regex("(?i)\\b((hoy|pasado mañana|mañana|el (lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo))( a las \\d{1,2}(:\\d{2})?\\s?(am|pm)?)?|a las \\d{1,2}(:\\d{2})?\\s?(am|pm)?)(?![\\p{L}\\d])").find(item.text)?.value
                        val title = item.text.replace(Regex("(?i)^(tengo que|hay que|debo|recuérdame|recuerdame|no olvidar)\\s+"), "").replaceFirstChar { it.uppercase() }
                        val r = ctx.tool("create_task", jsonOf("title" to SkillText.truncate(title, 180), "due" to due, "remind" to (due != null)))
                        if (!r.ok) return Outcome.fromTool(r, "Crear la tarea")
                        "tarea: ${r.message}"
                    }
                    "memory" -> {
                        val r = ctx.tool("remember", jsonOf("content" to item.text.replace(Regex("(?i)^(recuerda que|recordar que)\\s+"), "")))
                        if (!r.ok) return Outcome.fromTool(r, "Guardar el recuerdo")
                        "memoria: ${r.message}"
                    }
                    "expense" -> {
                        val p = ExpenseParser.parse(item.text, ctx.time.zonedNow().toLocalDate(), ctx.time.zonedNow()) ?: return Outcome.invalid("No encontré un monto con moneda en la nota.")
                        val tx = ctx.transactions()
                        tx.save(tx.nextId(), Transaction(p.type, p.money.amount.toPlainString(), p.money.currency, p.category, p.description, p.date.toString(), source = "voice_memo"))
                        "movimiento: ${p.money.format()} en ${p.category}"
                    }
                    "note" -> {
                        val warnings = mutableListOf<String>()
                        val file = ctx.saveDoc(SkillText.fileName("nota ${item.createdAt.take(10)} ${SkillText.truncate(item.text, 30)}", "md"), "text/markdown", "# Nota de voz\n\n_${item.createdAt}_\n\n${item.text}\n", warnings)
                            ?: return Outcome.fail("save_failed", warnings.joinToString(" "), "Revisa el espacio disponible.")
                        "nota guardada en ${file.str("location")}"
                    }
                    else -> return Outcome.invalid("Destino no soportado.")
                }
                col.save(id, item.copy(status = "converted", convertedTo = target))
                Outcome.done("Convertí la nota $id en $result", jsonOf("action" to "convert", "item" to view(id, item.copy(status = "converted", convertedTo = target))), src)
            }
            "archive" -> {
                val id = input.str("item_id") ?: return Outcome.invalid("Falta item_id.")
                val item = col.get(id) ?: return Outcome.fail("not_found", "No existe la nota $id.", "Pide la bandeja.")
                col.save(id, item.copy(status = "archived"))
                Outcome.done("Archivé la nota $id.", jsonOf("action" to "archive", "item" to view(id, item.copy(status = "archived"))), src)
            }
            else -> Outcome.invalid("Acción no soportada.")
        }
    }

    private fun label(s: String?) = when (s) { "task" -> "tarea"; "memory" -> "recuerdo"; "expense" -> "gasto"; else -> "idea" }
}

// ============================================================ visual_scene_assistant

class VisualSceneAssistantSkill : Skill {
    override val id = "visual_scene_assistant"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        ctx.requireServer("El análisis de imágenes")?.let { return it }
        val (img, stop) = ctx.image(input.str("source"))
        if (stop != null) return stop
        val task = if (input.str("task") == "read_text") "document_text" else "describe"
        ctx.progress("ANALIZANDO IMAGEN")
        val r = ctx.tool("analyze_image", jsonOf("image_base64" to Base64.getEncoder().encodeToString(img!!.bytes), "mime_type" to img.mimeType, "task" to task, "question" to input.str("question")))
        if (!r.ok) return Outcome.fromTool(r, "Analizar la imagen")
        val d = r.data as JsonObject
        val text = d.str("text").orEmpty()
        val data = jsonOf("task" to task, "text" to text, "model" to d.str("model"), "image_bytes" to img.bytes.size)
        return Outcome.done(SkillText.truncate(text.ifBlank { "El modelo no devolvió descripción." }, 700), data, listOf(SkillSource("Imagen del usuario analizada por ${d.str("model") ?: "el modelo de visión"}", kind = "imagen", retrievedAt = d.str("retrieved_at"))),
            listOf("Descripción generada por IA: puede contener errores. La imagen no se guarda en el teléfono ni en JARVIS."), listOf(ResultCard.Info(if (task == "describe") "Lo que veo" else "Texto de la imagen", listOf(text))))
    }
}

// ============================================================ barcode_business_card_scanner

class BarcodeBusinessCardScannerSkill : Skill {
    override val id = "barcode_business_card_scanner"

    override suspend fun execute(input: JsonObject, ctx: SkillContext): SkillOutcome {
        val (img, stop) = ctx.image(input.str("source"))
        if (stop != null) return stop
        val pixels = ctx.services.pixels.decode(img!!.bytes)
        val code = pixels?.let { BarcodeReader.read(it) }
        return if (input.str("mode") == "business_card") card(ctx, input, img, code) else barcode(ctx, input, code, pixels == null)
    }

    private suspend fun barcode(ctx: SkillContext, input: JsonObject, code: BarcodeReader.Barcode?, undecodable: Boolean): SkillOutcome {
        if (code == null) return Outcome.fail("no_barcode", if (undecodable) "No pude abrir la imagen." else "No encontré un código de barras legible en la imagen.", "Acerca la cámara, con buena luz y el código completo en el encuadre.")
        BarcodeReader.contactFromQr(code.text)?.let { return contact(ctx, input, it, "QR (vCard)", verified = true) }
        val warnings = mutableListOf<String>()
        val sources = mutableListOf(SkillSource("Lectura del código en el teléfono (ZXing)", kind = "local"))
        var product: JsonObject? = null
        if (code.text.all { it.isDigit() } && code.text.length in 8..14) {
            val r = ctx.tool("lookup_product", jsonOf("barcode" to code.text))
            if (r.ok) { product = r.data as JsonObject; if (product.str("name") != null) sources += SkillSource("Open Food Facts", product.str("source_url"), "base abierta (ODbL)", ctx.nowIso()) }
            else warnings += "No se pudo consultar Open Food Facts: ${r.message}"
        } else if (code.text.startsWith("http")) warnings += "El código contiene un enlace; no lo abro automáticamente."
        var inventory: Map<String, Any?>? = null
        if (input.bool("add_to_inventory") == true) {
            val qty = input.double("quantity") ?: 1.0
            val col = ctx.inventory()
            val existing = col.all().firstOrNull { it.second.scope == "business" && it.second.barcode == code.text }
            val name = product?.str("name") ?: input.str("name") ?: "Producto ${code.text}"
            val (id, item) = existing?.let { (id, i) -> id to i.copy(quantity = i.quantity + qty) } ?: (col.nextId() to InventoryItem("business", name, qty, barcode = code.text))
            ctx.services.store.putAll(listOf(col.record(id, item), ctx.movements().record(ctx.movements().nextId(), StockMovement(id, qty, "entrada por escaneo", ctx.nowIso(), item.quantity))))
            inventory = mapOf("id" to id, "name" to item.name, "quantity" to item.quantity, "created" to (existing == null))
        }
        val name = product?.str("name")
        val data = jsonOf("mode" to "barcode", "code" to code.text, "format" to code.format, "product" to product, "inventory" to inventory)
        val msg = "Código ${code.format}: ${code.text}." + (name?.let { " Producto: $it" + (product?.str("brands")?.let { b -> " ($b)" } ?: "") + "." } ?: if (product != null) " No está en Open Food Facts." else "") +
            (inventory?.let { " Inventario: ${it["name"]} → ${fmt(it["quantity"] as Double)}." } ?: "")
        return Outcome.done(msg, data, sources, warnings)
    }

    private suspend fun card(ctx: SkillContext, input: JsonObject, img: MediaFile, code: BarcodeReader.Barcode?): SkillOutcome {
        code?.let { c -> BarcodeReader.contactFromQr(c.text)?.let { return contact(ctx, input, it, "QR (vCard) de la tarjeta", verified = true) } }
        ctx.requireServer("Leer tarjetas de presentación")?.let { return it }
        ctx.progress("LEYENDO TARJETA")
        val r = ctx.tool("analyze_image", jsonOf("image_base64" to Base64.getEncoder().encodeToString(img.bytes), "mime_type" to img.mimeType, "task" to "business_card"))
        if (!r.ok) return Outcome.fromTool(r, "Leer la tarjeta")
        val fields = (r.data as JsonObject).obj("fields")?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { k to it } }?.toMap().orEmpty()
        if (fields["name"] == null && fields["company"] == null) return Outcome.fail("unreadable_card", "No pude leer un nombre ni una empresa en la tarjeta.", "Toma la foto de frente, con buena luz y sin reflejos.")
        return contact(ctx, input, fields, "tarjeta leída por IA", verified = false)
    }

    private suspend fun contact(ctx: SkillContext, input: JsonObject, f: Map<String, String>, origin: String, verified: Boolean): SkillOutcome {
        val warnings = mutableListOf<String>()
        if (!verified) warnings += "Datos extraídos por IA de una imagen: verifícalos antes de usarlos."
        f["email"]?.let { if (!Regex("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$").matches(it)) warnings += "El correo leído no parece válido: $it" }
        var saved: String? = null
        if (input.bool("save_contact") == true) {
            val summary = listOfNotNull(f["name"], f["company"], f["phone"], f["email"]).joinToString(" · ")
            if (!ctx.confirm("Guardar contacto ($origin): $summary")) return Outcome.notConfirmed("No guardé el contacto.")
            val col = ctx.contacts()
            saved = col.nextId()
            col.save(saved, Contact(f["name"] ?: f["company"]!!, f["company"], f["title"], f["phone"], f["email"], f["website"], f["address"], origin, ctx.nowIso(), verified))
        }
        val data = jsonOf("mode" to "business_card", "origin" to origin, "contact" to f, "verified" to verified, "saved_contact_id" to saved)
        return Outcome.done("Contacto: " + listOfNotNull(f["name"], f["company"], f["phone"], f["email"]).joinToString(", ") + (if (saved != null) " (guardado, id $saved)." else "."), data,
            listOf(SkillSource(origin.replaceFirstChar { it.uppercase() }, kind = "imagen")), warnings,
            listOf(ResultCard.Info("Tarjeta de presentación", f.map { (k, v) -> "$k: $v" })))
    }
}
