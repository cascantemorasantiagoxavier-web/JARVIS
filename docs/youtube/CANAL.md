# Canal de YouTube: "Construyendo JARVIS"

Kit de lanzamiento listo para copiar y pegar. Temática: construir en público un asistente de IA personal (JARVIS) para Android con Kotlin, Compose, backend Node.js y voz en tiempo real.

---

## 1. Crear el canal (3 minutos, desde tu cuenta de Google)

1. Entra a https://youtube.com e inicia sesión.
2. Avatar (arriba a la derecha) → **Crear un canal**. Usa un **nombre personalizado** (cuenta de marca), no tu nombre personal.
3. **Nombre:** `Construyendo JARVIS`
4. **Identificador (@):** `@ConstruyendoJARVIS` (alternativas: `@JarvisDevES`, `@HazTuJarvis`)
5. Verifica el teléfono en https://youtube.com/verify. Esto desbloquea miniaturas personalizadas y vídeos de más de 15 minutos.
6. YouTube Studio → **Personalización**: sube `avatar.svg` y `banner.svg` (exportados a PNG, ver la sección 3) y pega la descripción de la sección 2.

## 2. Textos del canal

**Descripción (pegar tal cual):**

> Estoy construyendo mi propio JARVIS: un asistente de IA personal para Android que escucha, habla, controla mi móvil, lee mis notas de Obsidian y busca en internet.
> Aquí lo enseño todo: código real, errores reales y cómo lo arreglo. Kotlin, Jetpack Compose, Node.js, IA en tiempo real y voz.
> Nuevo vídeo cada semana. Si quieres construir el tuyo, suscríbete.

**Palabras clave del canal:** jarvis, asistente ia, inteligencia artificial, android, kotlin, jetpack compose, programación, nodejs, chatgpt, gemini, asistente de voz, construir en público

**Enlaces:** GitHub (repositorio público, cuando lo abras) · Instagram/TikTok para los shorts

## 3. Identidad visual

- `avatar.svg`: 800×800 px, el núcleo HUD de JARVIS
- `banner.svg`: 2560×1440 px, con el texto dentro de la zona segura de 1546×423 px
- Paleta: fondo `#05080F`, cian `#00E5FF`, azul `#0077FF`, texto `#E6F7FF`
- Para convertir a PNG: abre el SVG en el navegador y haz una captura, o usa https://svgtopng.com. También puedes usar `rsvg-convert -w 2560 banner.svg > banner.png`.

**Miniaturas:** fondo oscuro, una sola frase de 3 o 4 palabras en cian, tu cara o el HUD de la app a la derecha. Ejemplos: "LE DI VOZ", "JARVIS ME OBEDECE", "ESTO FALLÓ".

## 4. Los 10 primeros vídeos

| # | Título | Formato |
|---|--------|---------|
| 1 | Voy a construir mi propio JARVIS (y tú puedes también) | Tráiler / presentación, 3–5 min |
| 2 | Le di VOZ a mi JARVIS: TTS gratis en Android | Tutorial, 10–12 min |
| 3 | La interfaz HUD de Iron Man con Jetpack Compose | Tutorial |
| 4 | Por qué NUNCA debes meter tu API key en la app (y cómo lo hice bien) | Seguridad + backend en Render |
| 5 | JARVIS ahora busca en internet en tiempo real | Integración Gemini y búsqueda web |
| 6 | Conecté JARVIS a mis notas de Obsidian | Integración |
| 7 | "Oye JARVIS": detección de palabra de activación | Tutorial |
| 8 | JARVIS controla mi móvil: llamadas, alarmas, apps | Demo + código |
| 9 | Desplegué mi backend GRATIS en Render (paso a paso) | Tutorial |
| 10 | 30 días construyendo JARVIS: lo que aprendí | Retrospectiva |

**Shorts (2 o 3 por semana):** recorta de cada vídeo largo el momento "wow" (JARVIS respondiendo con voz, el HUD animándose, un fallo gracioso). Formato vertical 9:16, de 20 a 45 segundos, con subtítulos grandes.

## 5. Guion del vídeo 1 (tráiler)

- **0:00 Gancho (5 s):** "Oye JARVIS, ¿qué tengo hoy?" Corte a la respuesta con voz. "Esto lo he programado yo, y te voy a enseñar a hacerlo."
- **0:10 Qué es:** asistente para Android que habla, escucha, busca en la web, lee tus notas y controla el móvil.
- **0:40 Demo rápida:** 3 clips de 5 s cada uno (voz, búsqueda web, HUD).
- **1:10 Cómo funciona:** diagrama simple: App Android → Backend en Render → IA. "Tus claves nunca van en el móvil."
- **2:00 Qué viene:** la lista de próximos vídeos en pantalla.
- **2:30 Cierre:** "Si quieres tu propio JARVIS, suscríbete. El próximo vídeo: le damos voz."

## 6. Plantilla de descripción por vídeo

```
[Una frase con la keyword principal: qué consigues en este vídeo]

⏱️ Capítulos
00:00 Intro
00:45 ...
05:30 Resultado final

💻 Código: [enlace GitHub]
🧠 Stack: Kotlin · Jetpack Compose · Node.js · Render

#JARVIS #InteligenciaArtificial #Android
```

## 7. Ritmo y checklist de publicación

- **Calendario:** 1 vídeo largo a la semana (martes o jueves, 18:00 hora local) y 2 o 3 shorts.
- **Antes de subir:** título de 60 caracteres como máximo con la keyword al principio · miniatura de 1280×720 · capítulos · 3 hashtags · pantalla final con el siguiente vídeo · subtítulos revisados · añadir a la lista de reproducción "Construyendo JARVIS".
- **Las primeras 48 h:** responde a todos los comentarios, fija uno con una pregunta y comparte el short del vídeo.
- **Meta del mes 1:** 4 vídeos largos, 10 shorts y 100 suscriptores. Para monetizar (YPP) hacen falta 1.000 suscriptores y 4.000 h de visualización, o 10 M de vistas en shorts.
