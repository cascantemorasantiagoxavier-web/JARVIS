package com.example.jarvis

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.BusinessSearchResult
import com.example.jarvis.core.business.CategoryInfo
import com.example.jarvis.core.business.FieldStatus
import com.example.jarvis.core.business.FieldValue
import com.example.jarvis.core.business.ProspectService
import com.example.jarvis.core.business.SaveProspectResult
import com.example.jarvis.core.memory.MemoryKind
import com.example.jarvis.core.memory.MemoryService
import com.example.jarvis.core.memory.RememberResult
import com.example.jarvis.core.model.ChatEntry
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.Role
import com.example.jarvis.core.notes.MarkdownChunker
import com.example.jarvis.core.notes.NotePrivacy
import com.example.jarvis.core.notes.NoteSearchService
import com.example.jarvis.core.skills.StoredRecord
import com.example.jarvis.data.db.JarvisDatabase
import com.example.jarvis.data.repo.RoomConversationStore
import com.example.jarvis.data.repo.RoomMemoryRepository
import com.example.jarvis.data.repo.RoomNoteIndex
import com.example.jarvis.data.repo.RoomProspectRepository
import com.example.jarvis.data.repo.RoomRecordStore
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Persistencia real con SQLite (Robolectric): sobrevive a cerrar y reabrir la base de datos. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = { Instant.parse("2025-09-29T13:42:00Z") }

    @Test fun skillRecordsSurviveCloseAndReopen() = runBlocking {
        val name = "test-skills.db"
        context.deleteDatabase(name)
        var db = JarvisDatabase.build(context, name)
        var store = RoomRecordStore(db)
        store.putAll(listOf(StoredRecord("finance.transactions", "1", "{\"amount\":\"3500\"}", 1), StoredRecord("finance.transactions", "2", "{\"amount\":\"1200\"}", 2)))
        store.put(StoredRecord("finance.transactions", "1", "{\"amount\":\"3600\"}", 3)) // reemplaza, no duplica
        db.close()

        db = JarvisDatabase.build(context, name)
        store = RoomRecordStore(db)
        assertEquals(listOf("2", "1"), store.list("finance.transactions").map { it.id }) // orden por updatedAt
        assertEquals("{\"amount\":\"3600\"}", store.get("finance.transactions", "1")!!.json)
        assertTrue(store.delete("finance.transactions", "2"))
        assertTrue(store.list("inventory.items").isEmpty())
        db.close()
    }

    @Test fun memorySurvivesCloseAndReopen() = runBlocking {
        val name = "test-memory.db"
        context.deleteDatabase(name)
        var db = JarvisDatabase.build(context, name)
        val saved = MemoryService(RoomMemoryRepository(db), now).remember("Prefiero respuestas directas", MemoryKind.PREFERENCE) as RememberResult.Saved
        db.close()

        db = JarvisDatabase.build(context, name)
        val svc = MemoryService(RoomMemoryRepository(db), now)
        assertEquals("Prefiero respuestas directas", svc.recall("¿cómo prefiero las respuestas?").first().content)
        assertTrue(svc.remember("prefiero respuestas directas", MemoryKind.PREFERENCE) is RememberResult.Duplicate)
        assertEquals("Prefiero respuestas breves y directas", svc.update(saved.item.id, "Prefiero respuestas breves y directas")!!.content)
        assertTrue(svc.forget(saved.item.id))
        db.close()

        db = JarvisDatabase.build(context, name)
        assertTrue(MemoryService(RoomMemoryRepository(db), now).all().isEmpty())
        db.close()
    }

    @Test fun prospectsAreUniquePerBusinessAndKeepSnapshot() = runBlocking {
        val db = JarvisDatabase.build(context, null)
        val svc = ProspectService(RoomProspectRepository(db), now)
        val b = Business(id = "osm:node:1", name = FieldValue("Súper Uno", FieldStatus.FOUND, listOf("osm")), phone = FieldValue(null))
        val first = svc.save(b, "Interesado") as SaveProspectResult.Saved
        assertTrue(svc.save(b) is SaveProspectResult.AlreadyExists)
        assertEquals(FieldStatus.UNAVAILABLE, svc.list().single().snapshot()!!.phone.status)
        assertEquals("Interesado", svc.list().single().notes)
        assertEquals(first.prospect.id, svc.list().single().id)
        db.close()
    }

    @Test fun chatHistoryRestoresTextAndBusinessCards() = runBlocking {
        val db = JarvisDatabase.build(context, null)
        val store = RoomConversationStore(db)
        val result = BusinessSearchResult(category = CategoryInfo("supermarket", "Supermercado"), total = 0, results = emptyList(), retrievedAt = "2025-09-29T13:42:00Z")
        store.append(ChatEntry(0, Role.USER, "Busca supermercados", now()))
        store.append(ChatEntry(0, Role.ASSISTANT, "No encontré.", now(), cards = listOf(ResultCard.Businesses(result))))
        val back = store.recent(10)
        assertEquals(listOf("Busca supermercados", "No encontré."), back.map { it.text })
        assertTrue(back.last().cards.single() is ResultCard.Businesses)
        db.close()
    }

    @Test fun noteFullTextSearchIgnoresAccentsAndExcludesPrivateForModel() = runBlocking {
        val db = JarvisDatabase.build(context, null)
        val index = RoomNoteIndex(db)
        val privacy = NotePrivacy()
        index.replaceNote("Trabajo/plan.md", MarkdownChunker.chunk("Trabajo/plan.md", "# Plan\nExportación de café a Heredia", 1, privacy))
        index.replaceNote("Privado/deudas.md", MarkdownChunker.chunk("Privado/deudas.md", "# Deudas\nCafé y préstamos", 1, privacy))
        val svc = NoteSearchService(index)
        assertEquals(listOf("Trabajo/plan.md"), svc.search("exportacion cafe", forModel = true).map { it.notePath })
        assertTrue(svc.search("cafe", forModel = false).any { it.notePath == "Privado/deudas.md" })
        index.removeNotesNotIn(setOf("Trabajo/plan.md"))
        assertEquals(1, index.stats().notes)
        db.close()
    }
}
