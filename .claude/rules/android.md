# Reglas: Android (`app/Android`)

## Módulos
- `:core` es Kotlin/JVM puro. Nada de `android.*` ni AndroidX ahí. Toda la lógica que se pueda
  probar sin teléfono va en `:core` (cerebro, herramientas, intenciones, Realtime, memoria, notas…).
- `:app` solo adapta: Room, DataStore, WorkManager, intents, audio, WebRTC, UI Compose.
- Las interfaces que `:app` implementa están en `:core` (`DeviceGateway`, `RealtimeTransport`,
  `RealtimeConnector`, `VoiceEventSink`, repositorios, `NoteIndexRepository`…).
- Dependencias nuevas: añadir al catálogo `gradle/libs.versions.toml`, con versión exacta y licencia
  compatible. Anotar en la KB si la versión no pudo verificarse.

## Arquitectura
- MVVM + `StateFlow`. DI manual en `AppContainer` (sin Hilt). `viewModel { … }` con fábrica lambda.
- Corrutinas en `container.scope` o `viewModelScope`; nunca `GlobalScope`.
- Persistencia: Room (`JarvisDatabase`, esquemas exportados a `app/schemas`). Al cambiar una entidad,
  subir `version` y escribir la migración; no usar `fallbackToDestructiveMigration` en release.
- Ajustes: un JSON serializable en DataStore (`SettingsRepository`). Secretos solo en `SecretStore`
  (AES-GCM, clave en Android Keystore).

## Interfaz (HUD)
- Fondo negro, paleta ámbar (`HudPalette`) con alternativa cian. Fuentes OFL: Orbitron y Rajdhani.
- La pantalla de inicio reproduce el diseño de referencia: barra superior (marca, reloj, clima),
  apps a la izquierda, núcleo holográfico al centro, actividad a la derecha, barra de voz abajo.
  En vertical se usan pestañas.
- El mapa NUNCA se abre por defecto: solo con `show_on_map` o un botón explícito.
- Etiquetas de prueba estables: `home`, `core`, `map`, `chat_input`, `chat_send`, `chat_list`.
- Respetar `reduceMotion`. Todo botón con `contentDescription` en español.
- El globo no debe asignar memoria por fotograma (`GlobeBuffers`).

## Permisos
- Pedirlos en contexto (`PermissionBroker`), nunca todos al arrancar.
- Micrófono: solo cuando el usuario inicia la voz o activa la palabra clave.
- Servicio en primer plano tipo `microphone` solo mientras la palabra clave o una conversación está
  activa; siempre con notificación y acción "Detener".
- Calendario: solo lectura. Las inserciones se hacen con el intent del sistema (el usuario confirma).
- Llamadas: `ACTION_DIAL` (abre el marcador; nunca `ACTION_CALL`).

## Compilación
- Donde no haya Google Maven: `./gradlew -Pjarvis.coreOnly=true :core:test`.
- Sherpa-ONNX es opcional: el source set `sherpa` se usa solo si existe `app/libs/sherpa-onnx*.aar`.
