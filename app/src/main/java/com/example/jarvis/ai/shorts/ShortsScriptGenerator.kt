package com.example.jarvis.ai.shorts

import com.example.jarvis.core.di.JarvisBrain
import com.example.jarvis.core.di.JarvisRequest
import com.example.jarvis.core.di.RequestSource
import com.example.jarvis.domain.shorts.ShortMetadata
import com.example.jarvis.domain.shorts.ShortScript

object ShortsScriptGenerator {

    suspend fun generateScript(topic: String, targetDurationSeconds: Int = 30): ShortScript {
        val prompt = "Escribe un guion viral para un YouTube Short de $targetDurationSeconds segundos sobre '$topic'.\n" +
                "Estructura obligatoria:\n" +
                "HOOK: [Frase impactante inicial]\n" +
                "CONTEXTO: [Explicación breve]\n" +
                "CUERPO: [Dato principal curioso o relevante]\n" +
                "GIRO: [Dato sorpresivo final]\n" +
                "CTA: [Llamado a la acción para suscribirse]"

        val req = JarvisRequest(text = prompt, source = RequestSource.CHAT)
        val res = JarvisBrain.processRequest(req)
        val fullText = res.text

        val hookMatch = """HOOK:\s*([^\n]+)""".toRegex(RegexOption.IGNORE_CASE).find(fullText)?.groupValues?.get(1) ?: "¡Esto cambiará lo que piensas sobre $topic!"
        val contextMatch = """CONTEXTO:\s*([^\n]+)""".toRegex(RegexOption.IGNORE_CASE).find(fullText)?.groupValues?.get(1) ?: "La tecnología está avanzando a pasos agigantados."
        val mainBodyMatch = """CUERPO:\s*([^\n]+)""".toRegex(RegexOption.IGNORE_CASE).find(fullText)?.groupValues?.get(1) ?: "Investigadores han logrado descubrimientos sorprendentes."
        val twistMatch = """GIRO:\s*([^\n]+)""".toRegex(RegexOption.IGNORE_CASE).find(fullText)?.groupValues?.get(1) ?: "Lo más sorprendente es que esto ya está ocurriendo hoy."
        val ctaMatch = """CTA:\s*([^\n]+)""".toRegex(RegexOption.IGNORE_CASE).find(fullText)?.groupValues?.get(1) ?: "Suscríbete para más avances tecnológicos."

        return ShortScript(
            hook = hookMatch,
            context = contextMatch,
            mainBody = mainBodyMatch,
            twist = twistMatch,
            cta = ctaMatch,
            targetDurationSeconds = targetDurationSeconds
        )
    }

    suspend fun generateMetadata(topic: String, script: ShortScript): ShortMetadata {
        val titlePrompt = "Genera un título corto, muy llamativo y SEO de menos de 80 caracteres para un YouTube Short sobre '$topic'."
        val req = JarvisRequest(text = titlePrompt, source = RequestSource.CHAT)
        val res = JarvisBrain.processRequest(req)
        val title = res.text.replace("\"", "").take(90).ifBlank { "¡El secreto de $topic!" }

        val desc = "${script.hook}\n\n${script.mainBody}\n\nSuscríbete a JARVIS Tech para más datos sobre inteligencia artificial y tecnología."
        val hashtags = listOf("#Shorts", "#IA", "#Tecnologia", "#$topic".replace(" ", ""))

        return ShortMetadata(
            title = title,
            description = desc,
            hashtags = hashtags,
            tags = listOf("IA", topic, "YouTube Shorts", "Tecnologia")
        )
    }
}
