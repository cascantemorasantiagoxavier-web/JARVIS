package com.example.jarvis.core

import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.BusinessQuery
import com.example.jarvis.core.business.BusinessSearchResult
import com.example.jarvis.core.business.CategoryInfo
import com.example.jarvis.core.business.FieldStatus
import com.example.jarvis.core.business.FieldValue
import com.example.jarvis.core.intent.Intent
import com.example.jarvis.core.intent.IntentDetector
import com.example.jarvis.core.json.bool
import com.example.jarvis.core.json.int
import com.example.jarvis.core.json.str
import com.example.jarvis.core.json.strings
import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.tools.WorkingContext
import com.example.jarvis.core.util.FixedTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IntentDetectorTest {
    private val time = FixedTimeSource(Instant.parse("2025-09-29T13:42:00Z"))
    private val detector = IntentDetector(time)
    private val working = WorkingContext()

    private fun tool(text: String, w: WorkingContext = working): Intent.Tool {
        val i = detector.detect(text, w)
        assertTrue("\"$text\" debería ser herramienta, fue $i", i is Intent.Tool)
        return i as Intent.Tool
    }

    @Test fun businessSearchPhrasesFromSpec() {
        tool("JARVIS, busca supermercados en San José, Costa Rica").let {
            assertEquals("business_search", it.name)
            assertEquals("supermercados", it.args.str("category"))
            assertEquals("San José, Costa Rica", it.args.str("location"))
        }
        tool("Busca ferreterías en Alajuela").let { assertEquals("Alajuela", it.args.str("location")); assertEquals("ferreterias", it.args.str("category")) }
        tool("Busca distribuidores de alimentos en Heredia y organiza sus datos").let {
            assertEquals("distribuidores de alimentos", it.args.str("category"))
            assertEquals("Heredia", it.args.str("location"))
        }
        tool("Encuentra importadores de productos electrónicos").let {
            assertEquals("business_search", it.name)
            assertEquals("Costa Rica", it.args.str("location"))
        }
        tool("busca farmacias cerca de mí").let { assertEquals(true, it.args.bool("near_me")) }
    }

    @Test fun voiceAndTextProduceTheSameIntent() {
        val voice = tool("JARVIS, busca supermercados en Cartago")
        val text = tool("Busca supermercados en Cartago")
        assertEquals(voice, text)
    }

    @Test fun missingLocationAsksForIt() {
        val i = detector.detect("busca ferreterías", working)
        assertTrue(i is Intent.Clarify)
        val withDefault = IntentDetector(time) { "Heredia" }.detect("busca ferreterías", working) as Intent.Tool
        assertEquals("Heredia", withDefault.args.str("location"))
    }

    @Test fun deepResearchGoesToModelWithWebFallback() {
        val i = detector.detect("Encuentra negocios que puedan comprar este producto", working)
        assertTrue(i is Intent.NeedsModel && i.mode == AssistantMode.DEEP)
        val r = detector.detect("Investiga los requisitos para importar café a Costa Rica", working) as Intent.NeedsModel
        assertEquals("web_search", r.fallback!!.name)
    }

    @Test fun localFastIntents() {
        assertEquals("get_datetime", tool("¿Qué hora es?").name)
        assertEquals("get_datetime", tool("JARVIS, qué día es hoy").name)
        assertEquals("calculate", tool("¿Cuánto es 25 por 4?").name)
        assertEquals("calculate", tool("15% de 200").name)
        assertEquals("calculate", tool("2+2").name)
        tool("convierte 10 km a millas").let { assertEquals("convert_units", it.name); assertEquals("km", it.args.str("from")) }
        tool("cuántos grados fahrenheit son 30 celsius").let { assertEquals("convert_units", it.name); assertEquals("celsius", it.args.str("from")); assertEquals("grados fahrenheit", it.args.str("to")) }
        assertEquals(listOf("battery"), tool("¿Cuánta batería tengo?").args.strings("aspects"))
        assertEquals(listOf("network"), tool("¿Tengo conexión a internet?").args.strings("aspects"))
        assertEquals("open_app", tool("Abre Spotify").name)
        assertEquals("Spotify", tool("abre la app de Spotify").args.str("app"))
        tool("¿Qué clima hace en Cartago?").let { assertEquals("get_weather", it.name); assertEquals("Cartago", it.args.str("place")) }
        assertEquals("get_weather", tool("¿Qué tiempo hace?").name)
    }

    @Test fun memoryIntents() {
        tool("Recuerda que prefiero respuestas directas").let {
            assertEquals("remember", it.name)
            assertEquals("Prefiero respuestas directas", it.args.str("content"))
            assertEquals("PREFERENCE", it.args.str("kind"))
        }
        tool("recuerda que mi proveedor de café es Café Britt, es privado").let { assertEquals(true, it.args.bool("private")) }
        tool("¿Cuál es mi preferencia sobre las respuestas?").let { assertEquals("recall", it.name) }
        assertEquals("recall", tool("¿Qué sabes de mí?").name)
    }

    @Test fun taskIntents() {
        tool("Recuérdame llamar al proveedor mañana a las 3 pm").let {
            assertEquals("create_task", it.name)
            assertEquals("Llamar al proveedor", it.args.str("title"))
            assertEquals(true, it.args.bool("remind"))
            assertTrue(it.args.str("due")!!.contains("mañana"))
        }
        assertTrue(detector.detect("recuérdame comprar café", working) is Intent.Clarify)
        tool("Crea una tarea: preparar cotización para el viernes, es urgente").let {
            assertEquals("create_task", it.name)
            assertEquals("HIGH", it.args.str("priority"))
        }
        assertEquals("pending", tool("¿Cuáles son mis tareas?").args.str("filter"))
        assertEquals("overdue", tool("muéstrame las tareas vencidas").args.str("filter"))
        assertEquals("plan_day", tool("¿Qué tengo para hoy?").name)
        assertEquals(3, tool("completa la tarea 3").args.int("task_id"))
    }

    @Test fun webAndNotesIntents() {
        tool("Busca en internet el precio del dólar hoy en Costa Rica").let { assertEquals("web_search", it.name); assertEquals("el precio del dólar hoy en Costa Rica", it.args.str("query")) }
        tool("busca en mis notas el plan de marketing").let { assertEquals("search_notes", it.name); assertEquals("el plan de marketing", it.args.str("query")) }
    }

    private fun biz(id: String, name: String) = Business(id = id, name = FieldValue(name, FieldStatus.FOUND, listOf("osm")))

    private fun withResults(): WorkingContext = WorkingContext().apply {
        rememberBusinessSearch(
            BusinessSearchResult(
                query = BusinessQuery("supermercados", "San José"), category = CategoryInfo("supermarket", "Supermercado"), total = 3,
                results = listOf(biz("osm:node:1", "Súper Uno"), biz("osm:node:2", "Mega Dos"), biz("osm:way:3", "Tres Hermanos")), retrievedAt = "2025-09-29T13:42:00Z",
                nextOffset = 3,
            ),
        )
    }

    @Test fun mapOnlyWhenAskedAndFollowUps() {
        val w = withResults()
        tool("Muéstrame esos negocios en el mapa", w).let { assertEquals("show_on_map", it.name); assertEquals(3, it.args.strings("business_ids").size) }
        tool("dame la ruta al segundo", w).let { assertEquals("get_directions", it.name); assertEquals("osm:node:2", it.args.str("business_id")) }
        tool("¿Dónde queda Mega Dos?", w).let { assertEquals("open_location", it.name); assertEquals("osm:node:2", it.args.str("business_id")) }
        tool("llama al primero", w).let { assertEquals("call_phone", it.name); assertEquals("osm:node:1", it.args.str("business_id")) }
        tool("guarda el tercero como prospecto", w).let { assertEquals("save_prospect", it.name); assertEquals("osm:way:3", it.args.str("business_id")) }
        tool("organiza estos prospectos por ubicación", w).let { assertEquals("organize_prospects", it.name); assertEquals("canton", it.args.str("by")) }
        tool("muéstrame más", w).let { assertEquals("business_search", it.name); assertEquals(3, it.args.int("offset")) }
        // Una búsqueda de negocios NO pide el mapa
        assertEquals("business_search", tool("busca supermercados en Cartago", w).name)
    }

    @Test fun routeWithoutReferenceAsksWhich() {
        assertTrue(detector.detect("dame la ruta", withResults()) is Intent.Clarify)
        assertNull("sin resultados previos no hay a qué referirse", detector.detect("dame la ruta", WorkingContext()))
    }

    @Test fun chitChatGoesToModel() {
        assertNull(detector.detect("¿Qué opinas de la inteligencia artificial?", working))
        assertEquals(AssistantMode.FAST, detector.suggestMode("Hola JARVIS"))
        assertEquals(AssistantMode.BALANCED, detector.suggestMode("¿Qué diferencia hay entre una sociedad anónima y una de responsabilidad limitada?"))
    }
}
