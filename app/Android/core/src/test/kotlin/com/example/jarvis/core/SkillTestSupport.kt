package com.example.jarvis.core

import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.model.RequestSource
import com.example.jarvis.core.skills.SkillLlm
import com.example.jarvis.core.skills.SkillResult
import com.example.jarvis.core.tools.ToolContext
import com.example.jarvis.core.tools.WorkingContext
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Modelo de lenguaje de prueba: registra lo que recibe y responde un texto fijo. */
class FakeSkillLlm(var reply: (task: String, content: String) -> String = { _, _ -> "Respuesta del modelo de prueba." }) : SkillLlm {
    val calls = mutableListOf<Pair<String, String>>()
    override suspend fun complete(task: String, content: String, mode: AssistantMode): String {
        calls += task to content
        return reply(task, content)
    }
}

/** Servidor JARVIS simulado: responde por ruta con JSON fijo (como el backend real). */
class FakeBackend {
    val server = MockWebServer()
    val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    val requests = mutableListOf<Pair<String, String>>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                requests += path to request.body.readUtf8()
                return routes[path]?.invoke(request) ?: MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":"Ruta no encontrada."}}""")
            }
        }
        server.start()
    }

    fun json(path: String, body: String, code: Int = 200) { routes[path] = { MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body) } }
    val url: String get() = server.url("/").toString()
    fun close() = server.shutdown()
}

fun skillCtx(kit: TestKit, source: RequestSource = RequestSource.TEXT) = ToolContext(source, kit.ui, WorkingContext())

suspend fun TestKit.runSkill(id: String, input: JsonObject, source: RequestSource = RequestSource.TEXT): SkillResult = engine.run(id, input, skillCtx(this, source))

/** Genera un ZIP en memoria (DOCX, XLSX y ODT son ZIP con XML). */
fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z -> entries.forEach { (name, content) -> z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray()); z.closeEntry() } }
    return out.toByteArray()
}

fun docxOf(vararg paragraphs: String) = zipOf(
    "[Content_Types].xml" to "<Types/>",
    "word/document.xml" to "<w:document><w:body>" + paragraphs.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" } + "</w:body></w:document>",
)

fun xlsxOf(header: List<String>, rows: List<List<Any>>): ByteArray {
    val strings = (header + rows.flatten().filterIsInstance<String>()).distinct()
    fun col(i: Int) = ('A' + i).toString()
    fun cell(r: Int, c: Int, v: Any) = if (v is String) "<c r=\"${col(c)}$r\" t=\"s\"><v>${strings.indexOf(v)}</v></c>" else "<c r=\"${col(c)}$r\"><v>$v</v></c>"
    val sheet = "<worksheet><sheetData>" + (listOf(header) + rows).mapIndexed { ri, row -> "<row r=\"${ri + 1}\">" + row.mapIndexed { ci, v -> cell(ri + 1, ci, v) }.joinToString("") + "</row>" }.joinToString("") + "</sheetData></worksheet>"
    return zipOf("xl/sharedStrings.xml" to "<sst>" + strings.joinToString("") { "<si><t>$it</t></si>" } + "</sst>", "xl/worksheets/sheet1.xml" to sheet)
}
