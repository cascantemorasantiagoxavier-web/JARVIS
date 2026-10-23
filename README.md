# J.A.R.V.I.S.

Asistente personal en español para Android, inspirado en el JARVIS de Iron Man.

- **Voz natural** con OpenAI GPT Realtime (WebRTC): baja latencia, puedes interrumpirlo, usa
  herramientas mientras habla. Alternativa gratuita: voz local del teléfono.
- **Palabra clave "JARVIS"** detectada en el teléfono (Sherpa-ONNX, opcional), sin enviar audio a la nube.
- **Un solo cerebro** para chat, voz y botones: 33 herramientas compartidas (`shared/tools.json`).
- **Memoria persistente** y lectura selectiva de tu bóveda de **Obsidian** (las notas privadas no salen del teléfono).
- **Búsqueda web con fuentes** y **búsqueda de negocios en Costa Rica** (OpenStreetMap) con el estado
  de cada dato: encontrado, verificado, no disponible, contradictorio o aproximado. Nada inventado.
- **Prospectos**, **tareas y recordatorios**, calendario, clima, apps, cálculos y conversiones.
- **Interfaz HUD** ámbar con núcleo holográfico (globo terrestre animado). El mapa solo se abre cuando lo pides.
- **Controles de costo**: límites de sesiones, cierre por inactividad y consumo del día visible.

## Cómo empezar

1. Servidor: [`docs/SETUP.md`](docs/SETUP.md) → sección 1.
2. App: `scripts/build-apk.sh` (requiere Android SDK) → instala el APK.
3. En la app: Ajustes → URL del servidor + token → Probar conexión.

Hosting con HTTPS: [`docs/HOSTING.md`](docs/HOSTING.md).

## Estructura

| Carpeta | Contenido |
|---|---|
| `backend/` | Servidor Node/Express: guarda la clave de OpenAI, Realtime, chat, búsqueda, negocios |
| `shared/` | Herramientas (`tools.json`) y personalidad (`persona-es.md`) compartidas |
| `app/Android/core` | Lógica en Kotlin puro, probada sin Android |
| `app/Android/app` | App Android (Jetpack Compose) |
| `docs/` | Instalación, hosting, licencias |
| `scripts/` | Pruebas, compilación del APK, descarga de la palabra clave |
| `tests/` | Mapa de pruebas y plan de pruebas manuales |

Guía para agentes de código: [`CLAUDE.md`](CLAUDE.md). Base de conocimiento: `../JARVIS-KB/`.

## Estado (2026-10-23)

| Parte | Estado |
|---|---|
| Backend | 57 pruebas automáticas en verde (sin red) |
| Núcleo Kotlin | 67 pruebas automáticas en verde (incluye integración con el backend real) |
| App Android | Código completo; **no compilada todavía** con Android Gradle Plugin (el entorno de desarrollo no tenía acceso a Google Maven). Pruebas Robolectric escritas, no ejecutadas |
| Emulador / teléfono | **No probado** |
| Pruebas en vivo (OpenAI, OSM) | **No probadas** (sin acceso de red a esos servicios en el entorno de desarrollo) |
| APK | **No generado** todavía |

## Seguridad

La clave de OpenAI vive solo en el servidor (`backend/.env`). La app usa un token propio cifrado con
el Android Keystore. Detalles: [`.claude/rules/security.md`](.claude/rules/security.md).

## Licencias de terceros

Fuentes Orbitron y Rajdhani (SIL OFL 1.1, `docs/licenses/`). WebRTC (`io.github.webrtc-sdk:android`,
BSD-3), osmdroid (Apache-2.0), Sherpa-ONNX (Apache-2.0, opcional). Datos de mapas y negocios
© colaboradores de OpenStreetMap (ODbL). Máscara de tierra del globo derivada de Natural Earth
(dominio público). Clima: Open-Meteo (CC BY 4.0).
