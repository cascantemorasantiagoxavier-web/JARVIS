package com.example.jarvis.core

import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.FieldStatus
import com.example.jarvis.core.business.FieldValue
import com.example.jarvis.core.business.LatLon
import com.example.jarvis.core.business.ProspectService
import com.example.jarvis.core.business.ProspectStatus
import com.example.jarvis.core.business.SaveProspectResult
import com.example.jarvis.core.business.SourceInfo
import com.example.jarvis.core.memory.MemoryCommands
import com.example.jarvis.core.memory.MemoryKind
import com.example.jarvis.core.memory.MemoryService
import com.example.jarvis.core.memory.RememberResult
import com.example.jarvis.core.notes.MarkdownChunker
import com.example.jarvis.core.notes.NotePrivacy
import com.example.jarvis.core.notes.NoteSearchService
import com.example.jarvis.core.tasks.ReminderScheduleResult
import com.example.jarvis.core.tasks.TaskFilter
import com.example.jarvis.core.tasks.TaskService
import com.example.jarvis.core.tasks.TaskStatus
import com.example.jarvis.core.util.FixedTimeSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.LocalDateTime

class MemoryNotesTasksTest {
    @get:Rule val tmp = TemporaryFolder()
    private val time = FixedTimeSource(Instant.parse("2025-09-29T13:42:00Z"))

    @Test fun memorySurvivesCloseAndReopen() = runTest {
        val file = tmp.newFile("memoria.tsv")
        val first = MemoryService(FileMemoryRepo(file)) { time.now() }
        val saved = first.remember("Prefiero respuestas directas", MemoryKind.PREFERENCE) as RememberResult.Saved
        assertTrue(saved.item.id > 0)

        // "Cerrar y reabrir": nueva instancia sobre el mismo almacenamiento
        val reopened = MemoryService(FileMemoryRepo(file)) { time.now() }
        val found = reopened.recall("¿cómo prefiero las respuestas?")
        assertEquals("Prefiero respuestas directas", found.first().content)

        // Actualizar
        assertEquals("Prefiero respuestas muy directas y breves", reopened.update(saved.item.id, "Prefiero respuestas muy directas y breves")!!.content)
        assertEquals("Prefiero respuestas muy directas y breves", MemoryService(FileMemoryRepo(file)) { time.now() }.all().single().content)

        // Eliminar
        assertTrue(reopened.forget(saved.item.id))
        assertTrue(MemoryService(FileMemoryRepo(file)) { time.now() }.all().isEmpty())
    }

    @Test fun memoryAvoidsDuplicatesAndUpdatesPreferences() = runTest {
        val m = MemoryService(InMemoryMemoryRepo()) { time.now() }
        m.remember("Mi proveedor de café es Café Britt", MemoryKind.PROJECT)
        assertTrue(m.remember("mi proveedor de cafe es cafe britt.", MemoryKind.PROJECT) is RememberResult.Duplicate)
        m.remember("Prefiero respuestas directas", MemoryKind.PREFERENCE)
        val upd = m.remember("Prefiero respuestas directas y breves", MemoryKind.PREFERENCE)
        assertTrue(upd is RememberResult.Updated)
        assertEquals(2, m.all().size)
    }

    @Test fun recallIsRelevantAndPrivacyIsRespected() = runTest {
        val m = MemoryService(InMemoryMemoryRepo()) { time.now() }
        m.remember("Prefiero respuestas directas", MemoryKind.PREFERENCE)
        m.remember("Mi cumpleaños es el 4 de marzo", MemoryKind.FACT)
        m.remember("La clave del portón es 4821", MemoryKind.FACT, isPrivate = true)
        m.remember("El proyecto Exportar Café busca compradores en Heredia", MemoryKind.PROJECT)
        assertEquals("Mi cumpleaños es el 4 de marzo", m.recall("cuándo es mi cumpleaños", 1).single().content)
        assertTrue(m.recall("proyecto café compradores", 2).first().content.contains("Exportar Café"))
        assertTrue("no recupera todo indiscriminadamente", m.recall("cumpleaños").size == 1)
        assertTrue(m.recall("clave del portón", includePrivate = false).isEmpty())
        assertEquals(1, m.recall("clave del portón", includePrivate = true).size)
        assertEquals(listOf("Prefiero respuestas directas"), m.preferencesForModel().map { it.content })
    }

    @Test fun rememberCommandParsing() {
        val c = MemoryCommands.parseRemember("JARVIS, recuerda que prefiero respuestas directas")!!
        assertEquals("Prefiero respuestas directas", c.content)
        assertEquals(MemoryKind.PREFERENCE, c.kind)
        assertTrue(MemoryCommands.parseRemember("recuerda que el código del candado es 1234, es privado")!!.isPrivate)
        assertNull(MemoryCommands.parseRemember("¿recuerdas la película?"))
    }

    private val privacy = NotePrivacy()

    @Test fun markdownChunkingWithFrontmatterTagsAndLinks() {
        val md = """
            ---
            title: Plan comercial 2026
            tags: [ventas, cafe]
            ---
            # Plan comercial
            Objetivo: vender café a supermercados de [[Heredia]].
            ## Prospectos
            Contactar a distribuidores mayoristas. #prioridad
            ```
            # esto es código, no un encabezado #nota
            ```
        """.trimIndent()
        val chunks = MarkdownChunker.chunk("Trabajo/plan.md", md, 1L, privacy)
        assertEquals(2, chunks.size)
        assertEquals("Plan comercial 2026", chunks[0].noteTitle)
        assertEquals("Plan comercial › Prospectos", chunks[1].heading)
        assertTrue(chunks[0].tags.containsAll(setOf("ventas", "cafe", "prioridad")))
        assertFalse(chunks[0].tags.contains("nota"))
        assertEquals(setOf("Heredia"), chunks[0].links)
        assertFalse(chunks[0].isPrivate)
    }

    @Test fun notePrivacyRules() {
        assertTrue(MarkdownChunker.chunk("Diario/hoy.md", "---\nprivado: true\n---\ntexto", 1, privacy).all { it.isPrivate })
        assertTrue(MarkdownChunker.chunk("x.md", "texto #privado", 1, privacy).all { it.isPrivate })
        assertTrue(MarkdownChunker.chunk("Privado/salud.md", "texto", 1, privacy).all { it.isPrivate })
        assertTrue(MarkdownChunker.chunk("x.md", "---\njarvis: no\n---\ntexto", 1, privacy).all { it.isPrivate })
        assertFalse(MarkdownChunker.chunk("Proyectos/x.md", "texto normal", 1, privacy).any { it.isPrivate })
    }

    @Test fun noteSearchIsSelectiveAndExcludesPrivateForModel() = runTest {
        val index = InMemoryNoteIndex()
        index.replaceNote("Trabajo/plan.md", MarkdownChunker.chunk("Trabajo/plan.md", "# Plan\nVender café a supermercados de Heredia y Cartago.", 1, privacy))
        index.replaceNote("Recetas/pan.md", MarkdownChunker.chunk("Recetas/pan.md", "# Pan\nHarina, agua y sal.", 1, privacy))
        index.replaceNote("Privado/finanzas.md", MarkdownChunker.chunk("Privado/finanzas.md", "# Finanzas\nVentas de café: deudas con el banco.", 1, privacy))
        val svc = NoteSearchService(index)
        val forModel = svc.search("ventas de café supermercados", forModel = true)
        assertEquals(listOf("Trabajo/plan.md"), forModel.map { it.notePath })
        assertEquals("[[Plan]] › Plan", forModel.single().citation)
        val local = svc.search("ventas de café", forModel = false)
        assertTrue(local.any { it.notePath == "Privado/finanzas.md" })
        val budget = svc.search("café", forModel = false, maxCharsTotal = 40)
        assertTrue(budget.sumOf { it.excerpt.length } <= 60)
    }

    @Test fun tasksCreateRemindListCompleteDelete() = runTest {
        val repo = InMemoryTaskRepo()
        val sched = FakeScheduler(exactAllowed = false)
        val svc = TaskService(repo, sched, time)
        val c = svc.create("Llamar al proveedor", dueText = "mañana a las 3 pm", remind = true)
        assertEquals(LocalDateTime.of(2025, 9, 30, 15, 0), c.task.due)
        assertTrue(c.reminder is ReminderScheduleResult.Inexact)
        assertEquals(LocalDateTime.of(2025, 9, 30, 15, 0), sched.scheduled[c.task.id])
        val noDate = svc.create("Revisar inventario", remind = true)
        assertNull("sin fecha no se programa aviso", noDate.reminder)
        svc.create("Pagar luz", dueText = "hoy")
        val overdueTask = repo.insert(c.task.copy(id = 0, title = "Enviar factura", due = LocalDateTime.of(2025, 9, 28, 10, 0), hasTime = true, remind = false))
        assertEquals(listOf("Enviar factura"), svc.list(TaskFilter.OVERDUE).map { it.title })
        assertEquals(listOf("Pagar luz"), svc.list(TaskFilter.TODAY).map { it.title })
        assertEquals(TaskStatus.DONE, svc.complete(c.task.id)!!.status)
        assertTrue(sched.cancelled.contains(c.task.id))
        assertTrue(svc.delete(overdueTask))
        assertEquals(3, svc.list(TaskFilter.ALL).size)
        try {
            svc.create("Algo", dueText = "cuando pueda")
            throw AssertionError("debería rechazar la fecha")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("No entendí la fecha"))
        }
    }

    @Test fun remindersAreRescheduledAfterReboot() = runTest {
        val repo = InMemoryTaskRepo()
        val svc = TaskService(repo, FakeScheduler(), time)
        svc.create("Tarea A", dueText = "mañana a las 9", remind = true)
        svc.create("Tarea B", dueText = "mañana a las 10", remind = false)
        val fresh = FakeScheduler()
        assertEquals(1, TaskService(repo, fresh, time).rescheduleAll())
        assertEquals(1, fresh.scheduled.size)
    }

    private fun business(id: String, name: String, canton: String?, province: String?, phone: String? = null) = Business(
        id = id,
        name = FieldValue(name, FieldStatus.FOUND, listOf("osm")),
        category = FieldValue("Supermercado", FieldStatus.FOUND, listOf("osm")),
        canton = FieldValue(canton, if (canton != null) FieldStatus.FOUND else FieldStatus.UNAVAILABLE),
        province = FieldValue(province, if (province != null) FieldStatus.FOUND else FieldStatus.UNAVAILABLE),
        phone = FieldValue(phone, if (phone != null) FieldStatus.FOUND else FieldStatus.UNAVAILABLE),
        coordinates = FieldValue(LatLon(9.9, -84.1), FieldStatus.APPROXIMATE, listOf("osm")),
        sources = mapOf("osm" to SourceInfo("OpenStreetMap", "https://www.openstreetmap.org/node/1", "ODbL", "2025-09-29T13:00:00Z")),
        retrievedAt = "2025-09-29T13:00:00Z",
    )

    @Test fun prospectsPersistWithSourcesAndOrganize() = runTest {
        val svc = ProspectService(InMemoryProspectRepo()) { time.now() }
        val saved = svc.save(business("osm:node:1", "Súper Uno", "Goicoechea", "San José", "+506 2222-3344"), note = "Interesado en café") as SaveProspectResult.Saved
        assertEquals("OpenStreetMap", saved.prospect.sourceName)
        assertTrue(saved.prospect.locationApproximate)
        assertEquals(Instant.parse("2025-09-29T13:00:00Z"), saved.prospect.foundAt)
        assertNotNull(saved.prospect.snapshot())
        assertEquals(FieldStatus.FOUND, saved.prospect.snapshot()!!.phone.status)
        assertTrue(svc.save(business("osm:node:1", "Súper Uno", "Goicoechea", "San José")) is SaveProspectResult.AlreadyExists)
        svc.save(business("osm:node:2", "Mega Dos", "Heredia", "Heredia"))
        svc.save(business("osm:node:3", "Tres", null, null))
        val upd = svc.update(saved.prospect.id, ProspectStatus.CONTACTADO, "Llamé el lunes", "Enviar cotización")!!
        assertEquals("Interesado en café\nLlamé el lunes", upd.notes)
        assertEquals("Enviar cotización", upd.nextAction)
        val byProvince = svc.organize("province")
        assertEquals(setOf("Heredia", "San José", "Provincia no disponible"), byProvince.keys)
        assertEquals(listOf("Súper Uno"), svc.list(ProspectStatus.CONTACTADO).map { it.name })
        assertEquals(listOf("Mega Dos"), svc.list(text = "heredia").map { it.name })
    }
}
