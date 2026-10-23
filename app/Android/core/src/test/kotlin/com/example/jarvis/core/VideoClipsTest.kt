package com.example.jarvis.core

import com.example.jarvis.core.json.jsonOf
import com.example.jarvis.core.json.obj
import com.example.jarvis.core.json.str
import com.example.jarvis.core.skills.SkillStatus
import com.example.jarvis.core.skills.impl.VideoClipsSkill
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clips con OpusClip y publicación en YouTube (servidor simulado con la forma de las rutas de clips). */
class VideoClipsTest {
    private val done = """{"projectId":"P123","stage":"COMPLETE","done":true,"failed":false,"clips":[
        {"number":1,"curationId":"CU1","title":"El mejor momento","durationSec":42},
        {"number":2,"curationId":"CU2","title":"Segundo clip","durationSec":59}]}"""

    @Test fun createStatusAndPublishPrivateByDefault() = runBlocking {
        val backend = FakeBackend()
        try {
            backend.json("/api/clips/projects", """{"projectId":"P123","stage":"PENDING"}""")
            backend.json("/api/clips/projects/P123", """{"projectId":"P123","stage":"RENDER","done":false,"failed":false,"clips":[]}""")
            backend.json("/api/clips/publish", """{"scheduled":false,"postId":"post-9","privacy":"private","account":"Canal Xavi"}""")
            val kit = TestKit(backendUrl = backend.url)

            val created = kit.runSkill("video_clips", jsonOf("action" to "create", "video_url" to "https://youtu.be/abc123"))
            assertEquals(created.summary, SkillStatus.COMPLETED, created.status)
            assertEquals("P123", created.data.str("project_id"))
            assertTrue(created.warnings.any { it.contains("derechos de autor") })
            val sent = backend.requests.single { it.first == "/api/clips/projects" }.second
            assertTrue(sent.contains("\"videoUrl\":\"https://youtu.be/abc123\"") && sent.contains("\"aspect\":\"portrait\""))

            val pending = kit.runSkill("video_clips", jsonOf("action" to "status"))
            assertTrue(pending.summary.contains("RENDER"))

            backend.json("/api/clips/projects/P123", done)
            val ready = kit.runSkill("video_clips", jsonOf("action" to "status"))
            assertTrue(ready.summary, ready.summary.contains("2 clips listos"))

            val posted = kit.runSkill("video_clips", jsonOf("action" to "publish", "clip" to 2))
            assertEquals(posted.summary, SkillStatus.COMPLETED, posted.status)
            val body = Json.parseToJsonElement(backend.requests.last { it.first == "/api/clips/publish" }.second).jsonObject
            assertEquals("CU2", body.str("curationId"))
            assertEquals("título del clip por defecto", "Segundo clip", body.str("title"))
            assertEquals("privado por defecto", "private", body.str("privacy"))
            assertNull(body["publishAt"])
            assertTrue(posted.warnings.any { it.contains("YouTube Studio") })
            assertEquals("post-9", posted.data.obj("post")!!.str("post_id"))
        } finally { backend.close() }
    }

    @Test fun publishingNeedsConfirmationAndValidClip() = runBlocking {
        val backend = FakeBackend()
        try {
            backend.json("/api/clips/projects", """{"projectId":"P123","stage":"PENDING"}""")
            backend.json("/api/clips/projects/P123", done)
            backend.json("/api/clips/publish", """{"scheduled":true,"scheduleId":"sch-1","publishAt":"2025-10-01T00:00:00Z","privacy":"unlisted","account":"Canal Xavi"}""")
            val kit = TestKit(backendUrl = backend.url)
            kit.runSkill("video_clips", jsonOf("action" to "create", "video_url" to "https://youtu.be/abc123"))

            kit.confirm = false
            assertEquals(SkillStatus.NOT_CONFIRMED, kit.runSkill("video_clips", jsonOf("action" to "publish", "clip" to 1)).status)
            assertTrue("no se publicó nada sin confirmar", backend.requests.none { it.first == "/api/clips/publish" })

            kit.confirm = true
            assertEquals("no_clip", kit.runSkill("video_clips", jsonOf("action" to "publish", "clip" to 7)).errorCode)
            // "mañana a las 6 pm" en Costa Rica (UTC-6) desde el 29/09/2025 07:42 → 2025-10-01T00:00:00Z
            val scheduled = kit.runSkill("video_clips", jsonOf("action" to "publish", "clip" to 1, "privacy" to "unlisted", "publish_at" to "mañana a las 6 pm"))
            assertEquals(scheduled.summary, SkillStatus.COMPLETED, scheduled.status)
            val body = Json.parseToJsonElement(backend.requests.last { it.first == "/api/clips/publish" }.second).jsonObject
            assertEquals("2025-10-01T00:00:00Z", body.str("publishAt"))
            assertEquals("unlisted", body.str("privacy"))
            assertEquals(SkillStatus.INVALID_INPUT, kit.runSkill("video_clips", jsonOf("action" to "publish", "clip" to 1, "publish_at" to "ayer")).status)
        } finally { backend.close() }
    }

    @Test fun notConfiguredOnServerIsReported() = runBlocking {
        val backend = FakeBackend()
        try {
            backend.json("/api/clips/projects", """{"error":{"code":"not_configured","message":"La función \"clips de video (OPUSCLIP_API_KEY)\" no está configurada en el servidor."}}""", 503)
            val r = TestKit(backendUrl = backend.url).runSkill("video_clips", jsonOf("action" to "create", "video_url" to "https://youtu.be/abc123"))
            assertEquals(SkillStatus.NEEDS_CONFIGURATION, r.status)
        } finally { backend.close() }
        assertEquals(SkillStatus.NEEDS_CONFIGURATION, TestKit().runSkill("video_clips", jsonOf("action" to "status")).status)
    }

    @Test fun localPhrasesAreUnderstood() {
        val s = VideoClipsSkill()
        assertEquals("create", s.parseText("JARVIS, haz clips de este video https://youtu.be/abc123.")!!.str("action"))
        assertEquals("https://youtu.be/abc123", s.parseText("haz clips de este video https://youtu.be/abc123.")!!.str("video_url"))
        val pub = s.parseText("sube el clip 2 a YouTube")!!
        assertEquals("publish", pub.str("action"))
        assertEquals("2", pub["clip"]!!.jsonPrimitive.content)
        assertEquals("status", s.parseText("¿ya están mis clips?")!!.str("action"))
        assertNull(s.parseText("pon música"))
    }
}
