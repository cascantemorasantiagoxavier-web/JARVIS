# Instalación y configuración de J.A.R.V.I.S.

Hay dos piezas:

1. **Servidor JARVIS** (`backend/`): guarda tu clave de OpenAI y hace las llamadas de pago.
2. **App Android** (`app/Android`): habla con tu servidor usando un token propio.

> **Regla de oro:** la clave de OpenAI va SOLO en `backend/.env` de tu servidor (o en el panel de
> secretos de tu hosting). Nunca en la app, en el APK, en el repositorio, en Obsidian ni en un chat.

Sin servidor la app ya hace muchas cosas: hora, cálculos, conversiones, tareas con avisos, memoria,
abrir apps, clima (Open-Meteo, gratis) y voz local del teléfono. El servidor añade la voz GPT
Realtime, el chat con IA, la búsqueda web y la de negocios.

---

## 1. Servidor

### Requisitos
- Node.js **22.9 o superior** (`node -v`).
- Una cuenta de OpenAI con saldo y una clave de API (platform.openai.com → *API keys*). Configura un
  límite de gasto mensual en *Limits*.

### Pasos
```bash
cd backend
npm ci
cp .env.example .env
npm run gen-token          # imprime un token aleatorio largo
```

Abre `backend/.env` con un editor y rellena:

| Variable | Qué poner |
|---|---|
| `JARVIS_CLIENT_TOKEN` | El token que imprimió `gen-token`. Lo pondrás también en la app. |
| `OPENAI_API_KEY` | Tu clave de OpenAI. **Solo aquí.** |
| `OSM_CONTACT` | Tu correo o web (lo exige la política de uso de OpenStreetMap). |
| `SEARXNG_URL` / `TAVILY_API_KEY` / `BRAVE_API_KEY` | Opcional: al menos uno para búsqueda web completa. Sin ninguno solo queda Wikipedia. |
| `UN_COMTRADE_API_KEY` | Opcional: estadísticas oficiales de importación y exportación (UN Comtrade, gratis con cuota diaria). |
| `OPUSCLIP_API_KEY` | Opcional: clips de video con OpusClip y publicación en YouTube (conecta tu canal dentro de OpusClip). Requiere plan con API (Pro, Max o Business). |
| `GOOGLE_MAPS_API_KEY` | Opcional: segunda fuente al verificar negocios (Google Places API (New); de pago con cuota gratuita). Activa "Places API (New)" en tu proyecto de Google Cloud y restringe la clave a esa API. |

Las demás APIs de Google Cloud (BigQuery, Cloud Storage, Logging, Monitoring, Calendar con clave
simple…) no se usan: JARVIS no las necesita. Ver `../JARVIS-KB/07-RESEARCH/apis-del-usuario.md`.

El resto tiene valores por defecto razonables (modelo `gpt-realtime-2.1-mini`, voz `cedar`, límites
de 20 sesiones de voz por hora y 60 por día).

```bash
npm start
curl http://localhost:8787/health
```

`/health` debe responder `"status":"ok"` y `"realtime": true` si la clave de OpenAI está puesta.

### Comprobar con datos reales (opcional)
```bash
npm run test:live
```
Hace llamadas reales (OpenAI, OpenStreetMap, búsqueda). Consume un poco de saldo.

---

## 2. Conectar el teléfono con el servidor

- **Pruebas en casa (APK debug):** teléfono y computadora en la misma Wi-Fi. URL:
  `http://IP-DE-TU-PC:8787` (por ejemplo `http://192.168.1.50:8787`). La versión debug permite
  `http://` solo para esto; el token viaja sin cifrar, así que úsalo solo en tu red de confianza.
- **Uso diario (APK release):** el servidor debe tener **HTTPS**. Opciones y costos en
  [HOSTING.md](HOSTING.md).

---

## 3. Compilar la app

### Sin Android Studio: GitHub Actions (recomendado)
Cada vez que se sube código a GitHub (ramas `main`, `master` o `jarvis-*`), el flujo
`.github/workflows/build.yml` ejecuta las pruebas del servidor y del núcleo, compila el APK y luego
las pruebas de la app. Para descargarlo:
1. GitHub → tu repositorio → pestaña **Actions** → la última ejecución en verde.
2. Abajo, en **Artifacts**, descarga `jarvis-debug-apk` (un `.zip` con el `.apk`).
3. Pásalo al teléfono e instálalo (Android pedirá permitir «instalar apps desconocidas»).

Si la compilación falla, los errores aparecen como anotaciones en la ejecución.
No usa ninguna clave: las pruebas simulan los servicios externos.

### Con Android Studio o línea de comandos

#### Requisitos
- JDK 17 o superior.
- Android SDK con la plataforma 35 (lo instala Android Studio). Si usas la línea de comandos,
  crea `app/Android/local.properties` con `sdk.dir=/ruta/al/Android/Sdk`.
- Acceso a internet a Google Maven (`dl.google.com`) y Maven Central.

### Opción A: Android Studio
Abre la carpeta `app/Android`, espera la sincronización de Gradle y pulsa *Run* con el teléfono
conectado (depuración USB activada).

### Opción B: línea de comandos
```bash
scripts/build-apk.sh            # pruebas + APK debug
```
El APK queda en `app/Android/app/build/outputs/apk/debug/app-debug.apk`. Instálalo con:
```bash
adb install -r app/Android/app/build/outputs/apk/debug/app-debug.apk
```
o cópialo al teléfono y ábrelo (Android pedirá permitir "instalar apps desconocidas").

### Palabra clave "JARVIS" sin tocar la pantalla (opcional)
```bash
scripts/fetch-sherpa-kws.sh     # descarga el AAR de Sherpa-ONNX y el modelo de palabras clave
scripts/build-apk.sh
```
Sin este paso la app funciona igual; solo falta la activación por voz (se muestra como no disponible).

---

## 4. Configurar la app

1. Abre JARVIS. En la bienvenida pulsa **Ir a Ajustes**.
2. **Servidor de JARVIS**: escribe la URL y el token (`JARVIS_CLIENT_TOKEN`). Pulsa **Guardar** y
   luego **Probar conexión**. Debe decir "En línea · voz ✓".
3. **Voz**: GPT Realtime (recomendado) o Voz local (gratis). Conexión "Unificada (recomendada)".
4. **Ubicación y clima**: busca tu ciudad o cantón. Guarda una zona por defecto para negocios.
5. **Memoria → Bóveda de Obsidian** (opcional): **Vincular bóveda** y elige la carpeta. JARVIS
   solo la lee. Las notas en `Privado/`, con `#privado`, `#confidencial`, `#no-jarvis` o
   `privado: true` en el frontmatter nunca se envían al modelo.

## 5. Permisos que pide y para qué

| Permiso | Cuándo se pide | Para qué |
|---|---|---|
| Micrófono | Al hablar por primera vez | Voz Realtime, voz local y palabra clave |
| Notificaciones | Al crear un recordatorio en Tareas o activar la palabra clave | Avisos y servicio de escucha |
| Alarmas exactas | Opcional: botón «Permitir alarmas exactas» en Ajustes | Sin él, Android puede retrasar unos minutos los avisos |
| Calendario (lectura) | Al preguntar por tu agenda | Leer eventos; crear eventos usa la app de calendario |
| Ubicación aproximada | Al pedir "cerca de mí" o el clima local | Búsqueda de negocios y clima |

La palabra clave mantiene un servicio en primer plano con notificación y botón **Detener**. Si
Android lo cierra, desactiva la optimización de batería para JARVIS (Ajustes → Palabra de activación).

## 6. Problemas frecuentes

| Síntoma | Causa probable |
|---|---|
| "Sin conexión" | URL mal escrita, servidor apagado, firewall, o `http://` en un APK release. |
| "Credencial del cliente inválida o ausente" | El token de la app no coincide con `JARVIS_CLIENT_TOKEN`. |
| "voz ✗ sin clave de OpenAI" | Falta `OPENAI_API_KEY` en `backend/.env` (reinicia el servidor). |
| "Límite … alcanzado (control de costos)" | Se superaron `REALTIME_MAX_SESSIONS_PER_DAY` o `CHAT_MAX_REQUESTS_PER_DAY`. |
| Búsqueda web pobre | No hay proveedor configurado (SearXNG, Tavily o Brave). |
| Negocios sin teléfono | OpenStreetMap no tiene ese dato. JARVIS no lo inventa: dice "no disponible". |
