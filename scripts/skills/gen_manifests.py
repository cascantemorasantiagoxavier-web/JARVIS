# Generador de manifiestos de habilidades (JARVIS/shared/skills/<id>.json).
# Uso: python3 -I gen.py /home/user/JARVIS/shared/skills
import json, os, sys

def S(maxLength=None, minLength=None, enum=None, **kw):
    d = {"type": "string"}
    if minLength is not None: d["minLength"] = minLength
    if maxLength is not None: d["maxLength"] = maxLength
    if enum is not None: d["enum"] = enum
    d.update(kw); return d
def N(minimum=None, maximum=None):
    d = {"type": "number"}
    if minimum is not None: d["minimum"] = minimum
    if maximum is not None: d["maximum"] = maximum
    return d
def I(minimum=None, maximum=None):
    d = {"type": "integer"}
    if minimum is not None: d["minimum"] = minimum
    if maximum is not None: d["maximum"] = maximum
    return d
def B(): return {"type": "boolean"}
def A(items, maxItems=None):
    d = {"type": "array", "items": items}
    if maxItems is not None: d["maxItems"] = maxItems
    return d
def O(props, req=(), addl=False):
    d = {"type": "object", "properties": props}
    if req: d["required"] = list(req)
    d["additionalProperties"] = addl
    return d
def OUT(props, req=()):
    return O(props, req, addl=True)
ANY_OBJ = {"type": "object"}

CANCEL = "Cancelable en cualquier momento (botón Detener o «cancela»). Los cambios se guardan al final: una cancelación no deja datos a medias."
CANCEL_RO = "Cancelable en cualquier momento (botón Detener o «cancela»). Solo lee datos: cancelar no tiene efectos."

SKILLS = {}
def skill(id, **kw):
    base = dict(id=id, tools=[], requiredPermissions=[], optionalPermissions=[], requiresExternalService=[], optionalServices=[],
                model="none", missing=[], localRoute=False)
    base.update(kw)
    SKILLS[id] = base

# ---------------------------------------------------------------- productividad
skill("writing_editor",
  name="Editor de textos", category="productividad",
  description="Revisa y mejora textos en español: corrige espacios, mayúsculas, signos de apertura (¿¡) y errores frecuentes, mide la legibilidad (Fernández-Huerta), señala frases largas y repeticiones y, con el servidor, reescribe según el objetivo (claridad, formal, breve…).",
  exampleUtterances=["corrige este texto: hola como estas, nos vemos mañana", "mejora la redacción de este párrafo", "revisa la ortografía de este correo", "hazme este texto más formal"],
  inputSchema=O({"text": S(20000, 1), "goal": S(enum=["corregir", "claridad", "formal", "informal", "breve", "persuasivo"]), "audience": S(120), "save_as": S(80)}, ["text"]),
  outputSchema=OUT({"stats": OUT({"words": I(0), "sentences": I(0), "avg_sentence_words": N(0), "readability": N(0, 100), "readability_label": S()}, ["words", "sentences"]),
                    "issues": A(OUT({"type": S(), "message": S(), "excerpt": S()}, ["type", "message"])),
                    "corrected_text": S(), "rewritten_text": S(), "used_model": B(), "file": ANY_OBJ}, ["stats", "issues", "corrected_text", "used_model"]),
  tools=["save_document"], requiresInternet=False, optionalServices=["jarvis_server"], model="optional",
  cost="Sin costo sin IA. Con reescritura por IA: tokens de texto del modelo del servidor (centavos de dólar por texto).",
  risk="local_write", limits={"timeoutSeconds": 60, "maxToolCalls": 2}, cancellation=CANCEL,
  errorHandling="Sin servidor o si el modelo falla, entrega igualmente la corrección automática y las observaciones, e indica que la reescritura con IA no está disponible.",
  maturity="implemented", privacy="El texto solo se envía al servidor si hay servidor configurado y se pidió reescritura (goal distinto de corregir).", localRoute=True,
  testCases=[{"name": "correccion_local", "input": {"text": "hola como estas?  nos vemos mañana. el proyecto va bien!"}, "expect": ["completed"]}])

skill("spreadsheet_analyst",
  name="Analista de hojas de cálculo", category="productividad",
  description="Analiza archivos CSV, TSV o XLSX: filas, columnas, tipos, vacíos, duplicados, sumas, promedios, medianas, valores más frecuentes y totales agrupados. Con el servidor puede responder preguntas sobre el resumen.",
  exampleUtterances=["analiza esta hoja de cálculo", "resume este archivo csv de ventas", "suma la columna monto agrupada por categoría", "cuántas filas tiene el excel"],
  inputSchema=O({"source": S(enum=["file", "text"]), "csv_text": S(2000000), "group_by": S(80), "sum_column": S(80), "question": S(500)}, ["source"]),
  outputSchema=OUT({"file_name": S(), "rows": I(0), "columns": A(OUT({"name": S(), "type": S()}, ["name", "type"])), "duplicates": I(0), "groups": A(ANY_OBJ), "answer": S()}, ["rows", "columns", "duplicates"]),
  requiresInternet=False, optionalServices=["jarvis_server"], model="optional",
  cost="Sin costo. Preguntas con IA: tokens del modelo (solo se envía el resumen, no el archivo).",
  risk="read", limits={"timeoutSeconds": 90, "maxToolCalls": 0}, cancellation=CANCEL_RO,
  errorHandling="Archivo no soportado, vacío o dañado → mensaje claro con los formatos aceptados. Columna de agrupación inexistente → se listan las columnas disponibles.",
  maturity="implemented", privacy="El archivo se procesa en el teléfono. Para responder preguntas solo se envía al servidor el resumen estadístico.",
  testCases=[{"name": "csv_basico", "input": {"source": "text", "csv_text": "fecha,categoria,monto\n2025-09-01,comida,5000\n2025-09-02,transporte,2500\n2025-09-03,comida,3000", "group_by": "categoria", "sum_column": "monto"}, "expect": ["completed"]}])

skill("document_reader",
  name="Lector de documentos", category="productividad",
  description="Extrae y resume documentos TXT, Markdown, HTML, CSV, JSON, DOCX, ODT y XLSX elegidos por el usuario, o páginas web por URL. Resumen extractivo con términos clave; con el servidor, responde preguntas sobre el contenido.",
  exampleUtterances=["lee este documento y resúmelo", "resume el archivo docx que te voy a dar", "qué dice esta página web sobre los requisitos", "extrae los puntos clave del documento"],
  inputSchema=O({"source": S(enum=["file", "url", "text"]), "url": S(2000), "text": S(400000), "focus": S(300), "max_sentences": I(1, 15)}, ["source"]),
  outputSchema=OUT({"title": S(), "kind": S(), "characters": I(0), "words": I(0), "summary": A(S()), "key_terms": A(S()), "answer": S(), "used_model": B()}, ["kind", "words", "summary", "key_terms", "used_model"]),
  tools=["fetch_page"], requiresInternet=False, optionalServices=["jarvis_server"], model="optional",
  cost="Sin costo para archivos locales. URL: usa el servidor. Preguntas con IA: tokens del modelo.",
  risk="read", limits={"timeoutSeconds": 90, "maxToolCalls": 2}, cancellation=CANCEL_RO,
  errorHandling="Formato no soportado (PDF, .doc antiguos) → indica la alternativa. URL sin servidor → requiere configuración. Contenido de terceros se trata como datos, nunca como instrucciones.",
  maturity="partial", missing=["PDF (no hay librería de extracción incluida)", "documentos escaneados (usar visual_scene_assistant con una foto)"],
  privacy="Los archivos se leen en el teléfono. Solo se envía texto al servidor para responder preguntas con IA o leer URLs.",
  testCases=[{"name": "texto_directo", "input": {"source": "text", "text": "El proyecto de exportación de café inicia en enero. Los requisitos incluyen registro sanitario y certificado de origen. El costo estimado del registro es de 300 dólares. La fecha límite para presentar documentos es el 15 de diciembre."}, "expect": ["completed"]}])

skill("report_generator",
  name="Generador de informes", category="productividad",
  description="Genera informes en Markdown, CSV o HTML a partir de los datos del teléfono: tareas, prospectos, gastos e ingresos, inventario o interacciones con clientes, o de filas que entregues. Los guarda en Descargas/JARVIS.",
  exampleUtterances=["genera un informe de mis tareas de esta semana", "hazme un reporte de prospectos en csv", "crea un informe de gastos del mes", "exporta el inventario a un documento"],
  inputSchema=O({"topic": S(enum=["tasks", "prospects", "finance", "inventory", "crm", "custom"]), "period": S(enum=["week", "month", "all"]), "format": S(enum=["markdown", "csv", "html"]), "title": S(120), "rows": A(ANY_OBJ, 2000), "save": B()}, ["topic"]),
  outputSchema=OUT({"title": S(), "format": S(), "sections": A(OUT({"heading": S(), "lines": A(S())}, ["heading", "lines"])), "file": ANY_OBJ, "preview": S()}, ["title", "format", "sections", "preview"]),
  tools=["save_document"], requiresInternet=False, cost="Sin costo.",
  risk="local_write", limits={"timeoutSeconds": 60, "maxToolCalls": 1}, cancellation=CANCEL,
  errorHandling="Sin datos para el tema → informe con la sección vacía explicada. Si no se puede guardar, devuelve el contenido igualmente.",
  maturity="implemented", privacy="Se genera en el teléfono; nada se envía fuera. El archivo queda en Descargas/JARVIS.", localRoute=True,
  testCases=[{"name": "tareas_markdown", "input": {"topic": "tasks", "format": "markdown", "save": False}, "expect": ["completed"]}])

skill("development_copilot",
  name="Copiloto de programación", category="productividad",
  description="Ayuda con código: detecta el lenguaje, cuenta líneas y señala problemas evidentes (secretos en el código, TODO, funciones muy largas) y, con el servidor, explica, revisa o propone código. Nunca ejecuta código.",
  exampleUtterances=["explícame este código", "revisa esta función de kotlin", "hay algún secreto expuesto en este código", "propón cómo mejorar este script de python"],
  inputSchema=O({"code": S(60000, 1), "task": S(enum=["explain", "review", "improve", "scan"]), "language": S(40), "question": S(500)}, ["code"]),
  outputSchema=OUT({"language": S(), "lines": I(0), "findings": A(OUT({"type": S(), "line": I(0), "message": S()}, ["type", "message"])), "answer": S(), "used_model": B()}, ["language", "lines", "findings", "used_model"]),
  requiresInternet=False, optionalServices=["jarvis_server"], model="optional",
  cost="Escaneo local sin costo. Explicación/revisión con IA: tokens del modelo.",
  risk="read", limits={"timeoutSeconds": 90, "maxToolCalls": 0}, cancellation=CANCEL_RO,
  errorHandling="Sin servidor → solo el análisis local, indicando que la explicación requiere el modelo. El código nunca se ejecuta.",
  maturity="implemented", privacy="Si el código contiene posibles secretos, no se envía al servidor y se avisa.",
  testCases=[{"name": "escaneo_secretos", "input": {"code": "val apiKey = \"sk-test-1234567890abcdefghijklmnop\"\nfun main() {\n  // TODO: borrar\n  println(apiKey)\n}", "task": "scan"}, "expect": ["completed"]}])

# ---------------------------------------------------------------- inventario
INV_ITEM_OUT = OUT({"id": S(), "name": S(), "quantity": N(), "unit": S(), "location": S()}, ["id", "name"])
skill("stock_control",
  name="Control de inventario del negocio", category="negocio",
  description="Inventario local del negocio: altas de productos, entradas y salidas con motivo, existencias bajas respecto al mínimo, valor del inventario por moneda y búsqueda por nombre o código de barras.",
  exampleUtterances=["agrega 20 unidades de café molido al inventario", "registra una venta de 3 cajas de té", "qué productos están por agotarse", "cuánto vale mi inventario"],
  inputSchema=O({"action": S(enum=["add_item", "record_movement", "list", "low_stock", "value", "find", "delete_item"]), "name": S(120), "item_id": S(20), "quantity": N(0, 1e9), "unit": S(20), "min_quantity": N(0, 1e9), "unit_cost": N(0, 1e12), "currency": S(enum=["CRC", "USD", "EUR"]), "delta": N(-1e9, 1e9), "reason": S(120), "barcode": S(20), "query": S(120)}, ["action"]),
  outputSchema=OUT({"action": S(), "items": A(INV_ITEM_OUT), "item": ANY_OBJ, "movement": ANY_OBJ, "totals": A(OUT({"currency": S(), "value": N()}, ["currency", "value"]))}, ["action"]),
  requiresInternet=False, cost="Sin costo.", risk="sensitive", limits={"timeoutSeconds": 20, "maxToolCalls": 0}, cancellation=CANCEL,
  errorHandling="Producto inexistente → sugiere coincidencias. Salida mayor que la existencia → se rechaza sin modificar nada. Borrar exige confirmación.",
  maturity="implemented", privacy="Datos solo en el teléfono.", localRoute=True,
  testCases=[{"name": "alta", "input": {"action": "add_item", "name": "Café molido 500 g", "quantity": 20, "unit": "bolsas", "min_quantity": 5, "unit_cost": 3500, "currency": "CRC"}, "expect": ["completed"]}])

skill("home_inventory",
  name="Inventario del hogar", category="hogar",
  description="Inventario personal de la casa: qué tienes, dónde está, cuánto vale, número de serie y garantías por vencer (útil para seguros y mudanzas).",
  exampleUtterances=["guarda en el inventario de la casa la refrigeradora en la cocina", "dónde guardé el taladro", "qué garantías vencen pronto", "cuánto vale lo que tengo en la casa"],
  inputSchema=O({"action": S(enum=["add_item", "list", "find", "warranties", "value", "delete_item"]), "name": S(120), "item_id": S(20), "location": S(80), "quantity": N(0, 1e6), "value": N(0, 1e12), "currency": S(enum=["CRC", "USD", "EUR"]), "purchase_date": S(10), "warranty_until": S(10), "serial": S(80), "notes": S(300), "query": S(120), "days": I(1, 730)}, ["action"]),
  outputSchema=OUT({"action": S(), "items": A(INV_ITEM_OUT), "item": ANY_OBJ, "totals": A(OUT({"currency": S(), "value": N()}, ["currency", "value"]))}, ["action"]),
  requiresInternet=False, cost="Sin costo.", risk="sensitive", limits={"timeoutSeconds": 20, "maxToolCalls": 0}, cancellation=CANCEL,
  errorHandling="Fechas inválidas → se pide formato AAAA-MM-DD. Borrar exige confirmación.",
  maturity="implemented", privacy="Datos solo en el teléfono (pueden incluir números de serie y valores).", localRoute=True,
  testCases=[{"name": "alta_casa", "input": {"action": "add_item", "name": "Taladro Bosch", "location": "bodega", "value": 45000, "currency": "CRC", "warranty_until": "2026-01-15"}, "expect": ["completed"]}])

skill("sales_coach",
  name="Entrenador de ventas", category="negocio",
  description="Revisa tu embudo de prospectos (estados, prospectos estancados, sin próxima acción), da retroalimentación sobre tu discurso de venta, entrena respuestas a objeciones frecuentes y propone seguimientos (opcionalmente como tareas).",
  exampleUtterances=["revisa mi embudo de ventas", "cómo respondo a un cliente que dice que está muy caro", "dame retroalimentación de mi discurso de venta", "qué prospectos necesitan seguimiento"],
  inputSchema=O({"action": S(enum=["pipeline_review", "pitch_feedback", "objection_practice", "follow_up_plan"]), "pitch_text": S(5000), "objection": S(300), "product": S(200), "create_tasks": B()}, ["action"]),
  outputSchema=OUT({"action": S(), "pipeline": ANY_OBJ, "recommendations": A(S()), "feedback": A(S()), "responses": A(OUT({"technique": S(), "text": S()}, ["technique", "text"])), "tasks_created": I(0), "used_model": B()}, ["action", "recommendations", "used_model"]),
  tools=["create_task"], requiresInternet=False, optionalServices=["jarvis_server"], model="optional",
  cost="Sin costo; con IA, tokens del modelo para retroalimentación personalizada.",
  risk="local_write", limits={"timeoutSeconds": 60, "maxToolCalls": 10}, cancellation=CANCEL,
  errorHandling="Sin prospectos → explica cómo crearlos con business_prospecting. Sin servidor → respuestas de la biblioteca local de objeciones.",
  maturity="implemented", privacy="El embudo se analiza en el teléfono; a la IA solo va el texto del discurso si lo pides.", localRoute=True,
  testCases=[{"name": "objecion_precio", "input": {"action": "objection_practice", "objection": "está muy caro"}, "expect": ["completed"]}])

# ---------------------------------------------------------------- asistente personal
skill("daily_briefing",
  name="Resumen del día", category="asistente",
  description="Resumen hablado del día: tareas con fecha y vencidas, eventos del calendario (si das permiso), pronóstico del clima, renovaciones próximas, inventario bajo y notas de voz pendientes.",
  exampleUtterances=["dame el resumen del día", "cómo viene mi día", "resumen de mañana", "briefing de hoy"],
  inputSchema=O({"day": S(enum=["today", "tomorrow"]), "include_weather": B()}),
  outputSchema=OUT({"day": S(), "sections": A(OUT({"heading": S(), "lines": A(S())}, ["heading", "lines"])), "spoken": S()}, ["day", "sections", "spoken"]),
  optionalPermissions=["calendar.read"], requiresInternet=False, optionalServices=["open_meteo"],
  cost="Sin costo (Open-Meteo es gratuito).", risk="read", limits={"timeoutSeconds": 30, "maxToolCalls": 0}, cancellation=CANCEL_RO,
  errorHandling="Sin permiso de calendario o sin Internet para el clima → resumen parcial que dice qué faltó.",
  maturity="implemented", privacy="Todo se calcula en el teléfono; solo se consulta el clima a Open-Meteo con la ubicación configurada.", localRoute=True,
  testCases=[{"name": "hoy", "input": {"day": "today", "include_weather": False}, "expect": ["completed", "partial"]}])

skill("task_manager",
  name="Gestor de tareas", category="asistente",
  description="Crea, lista, completa y borra tareas con recordatorios reales (AlarmManager) y muestra los recordatorios programados. Reutiliza las herramientas de tareas: mismo almacenamiento y reglas.",
  exampleUtterances=["crea una tarea de pagar el agua el viernes", "qué recordatorios tengo programados", "marca como hecha la tarea 3", "lista mis tareas pendientes"],
  inputSchema=O({"action": S(enum=["create", "list", "complete", "delete", "reminders"]), "title": S(200), "description": S(1000), "due": S(80), "priority": S(enum=["LOW", "MEDIUM", "HIGH"]), "remind": B(), "filter": S(enum=["pending", "today", "overdue", "done", "all"]), "task_id": I(1)}, ["action"]),
  outputSchema=OUT({"action": S(), "result": ANY_OBJ, "tasks": A(ANY_OBJ), "message": S()}, ["action"]),
  tools=["create_task", "list_tasks", "complete_task", "delete_task"], requiresInternet=False, cost="Sin costo.", risk="sensitive",
  limits={"timeoutSeconds": 20, "maxToolCalls": 2}, cancellation=CANCEL,
  errorHandling="Fecha no entendida → pide un formato como «mañana a las 3 pm». Borrar pide confirmación. Sin alarmas exactas → avisa que puede retrasarse.",
  maturity="implemented", privacy="Tareas solo en el teléfono.",
  testCases=[{"name": "crear", "input": {"action": "create", "title": "Pagar el agua", "due": "mañana"}, "expect": ["completed"]}])

skill("conversation_memory",
  name="Memoria persistente", category="asistente",
  description="Gestiona la memoria de largo plazo: recordar, buscar, corregir, olvidar (con confirmación), listar y exportar a Markdown. Los recuerdos privados no se envían al modelo ni se exportan sin confirmar.",
  exampleUtterances=["qué recuerdas de mí", "guarda en tu memoria que prefiero café sin azúcar", "olvida el recuerdo 4", "exporta mis recuerdos"],
  inputSchema=O({"action": S(enum=["remember", "recall", "update", "forget", "list", "export"]), "content": S(1000), "kind": S(enum=["PREFERENCE", "FACT", "PROJECT", "CONTACT", "OTHER"]), "private": B(), "memory_id": I(1), "query": S(300), "limit": I(1, 20), "include_private": B()}, ["action"]),
  outputSchema=OUT({"action": S(), "result": ANY_OBJ, "memories": A(ANY_OBJ), "exported": I(0), "file": ANY_OBJ}, ["action"]),
  tools=["remember", "recall", "update_memory", "forget", "save_document"], requiresInternet=False, cost="Sin costo.", risk="sensitive",
  limits={"timeoutSeconds": 20, "maxToolCalls": 2}, cancellation=CANCEL,
  errorHandling="Duplicados → avisa sin guardar dos veces. Olvidar o exportar privados → confirmación.",
  maturity="implemented", privacy="Memoria solo en el teléfono; los privados se muestran como [privado].", localRoute=True,
  testCases=[{"name": "listar", "input": {"action": "list"}, "expect": ["completed"]}])

skill("device_diagnostics",
  name="Diagnóstico del teléfono", category="asistente",
  description="Diagnóstico con datos que Android permite leer: modelo y versión, batería, red, almacenamiento, permisos de JARVIS, notificaciones, alarmas exactas, optimización de batería y estado del servidor, con recomendaciones.",
  exampleUtterances=["haz un diagnóstico del teléfono", "revisa el estado del celular", "por qué no me llegan los recordatorios", "cuánto almacenamiento libre tengo"],
  inputSchema=O({"checks": A(S(enum=["battery", "network", "storage", "permissions", "notifications", "alarms", "server"]), 7)}),
  outputSchema=OUT({"device": ANY_OBJ, "recommendations": A(S())}, ["device", "recommendations"]),
  tools=["get_device_status"], requiresInternet=False, cost="Sin costo.", risk="read", limits={"timeoutSeconds": 30, "maxToolCalls": 1}, cancellation=CANCEL_RO,
  errorHandling="Datos que Android no expone → «no disponible». Servidor caído → se indica sin bloquear el resto.",
  maturity="implemented", privacy="No sale del teléfono salvo la consulta de estado al servidor configurado.", localRoute=True,
  testCases=[{"name": "basico", "input": {"checks": ["battery", "network", "storage", "permissions"]}, "expect": ["completed"]}])

skill("maps_navigation",
  name="Mapas y navegación", category="asistente",
  description="Busca lugares (Open-Meteo/GeoNames) y, solo cuando lo pides, abre el mapa o la ruta en tu app de mapas para un lugar, unas coordenadas o un negocio encontrado.",
  exampleUtterances=["cómo llego a Puerto Viejo", "muéstrame en el mapa el parque central de Heredia", "busca la ubicación de Santa Ana", "llévame a la ferretería que encontraste"],
  inputSchema=O({"action": S(enum=["find_place", "open", "directions"]), "query": S(120), "lat": N(-90, 90), "lon": N(-180, 180), "label": S(120), "business_id": S(60)}, ["action"]),
  outputSchema=OUT({"action": S(), "places": A(ANY_OBJ), "opened": B()}, ["action", "opened"]),
  tools=["find_place", "open_map_point", "open_location", "get_directions"], requiresInternet=True, requiresExternalService=["open_meteo"],
  cost="Sin costo.", risk="read", limits={"timeoutSeconds": 30, "maxToolCalls": 3}, cancellation=CANCEL_RO,
  errorHandling="Lugar no encontrado → sugiere usar cantón o ciudad. Varias coincidencias → usa la primera y lo dice. Sin coordenadas reales → no abre nada.",
  maturity="implemented", privacy="Solo se envía el nombre del lugar al geocodificador.", localRoute=True,
  testCases=[{"name": "coordenadas", "input": {"action": "open", "lat": 9.9981, "lon": -84.1165, "label": "Heredia centro"}, "expect": ["completed"]}])

skill("obsidian_memory",
  name="Memoria de Obsidian", category="conocimiento",
  description="Consulta selectiva de tu bóveda de Obsidian: revisa el índice, busca solo fragmentos relevantes (nunca notas privadas), resume y conserva el enlace a cada nota original. No escribe ni duplica datos.",
  exampleUtterances=["qué dicen mis notas sobre el proyecto de café", "busca en mis notas sobre exportación", "resume lo que tengo en obsidian sobre clientes", "busca en la bóveda sobre la reunión con Ana"],
  inputSchema=O({"query": S(300, 2), "mode": S(enum=["search", "summary"]), "max_fragments": I(1, 12)}, ["query"]),
  outputSchema=OUT({"index": ANY_OBJ, "fragments": A(OUT({"note": S(), "path": S(), "excerpt": S()}, ["note", "path", "excerpt"])), "summary": A(S()), "used_model": B()}, ["index", "fragments", "summary", "used_model"]),
  requiredPermissions=[], requiresInternet=False, requiresExternalService=["obsidian_vault"], optionalServices=["jarvis_server"], model="optional",
  cost="Sin costo; el resumen con IA usa tokens del modelo (solo fragmentos, no notas completas).", risk="read", limits={"timeoutSeconds": 30, "maxToolCalls": 0}, cancellation=CANCEL_RO,
  errorHandling="Sin bóveda → requiere configuración. Índice vacío → pide reindexar. Sin resultados → lo dice; nunca inventa contenido de notas.",
  maturity="implemented", privacy="Notas privadas excluidas siempre. Al modelo solo van fragmentos y solo en modo resumen.", localRoute=True,
  testCases=[{"name": "buscar", "input": {"query": "café", "mode": "search"}, "expect": ["completed", "failed"]}])

skill("meeting_assistant",
  name="Asistente de reuniones", category="asistente",
  description="Prepara reuniones con datos autorizados (evento del calendario, notas, prospectos, tareas e interacciones relacionadas), estructura notas en decisiones y acciones y crea las tareas de seguimiento.",
  exampleUtterances=["prepara la reunión con Ana de mañana", "ordena estas notas de la reunión", "crea las tareas de seguimiento de la reunión", "haz la minuta de la reunión de hoy"],
  inputSchema=O({"action": S(enum=["prepare", "notes", "follow_up"]), "meeting": S(200), "date": S(enum=["today", "tomorrow", "week"]), "attendees": A(S(80), 20), "notes_text": S(30000), "create_tasks": B(), "save": B()}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  tools=["create_task", "save_document"], optionalPermissions=["calendar.read"], requiresInternet=False, optionalServices=["jarvis_server", "obsidian_vault"], model="optional",
  cost="Sin costo; sugerencias con IA usan tokens.", risk="local_write", limits={"timeoutSeconds": 60, "maxToolCalls": 12}, cancellation=CANCEL,
  errorHandling="Sin permiso de calendario → prepara igual y lo indica. Acciones sin fecha → tarea sin recordatorio.",
  maturity="implemented", privacy="Usa solo datos del teléfono que autorizaste (calendario, bóveda no privada, prospectos).",
  testCases=[{"name": "minuta", "input": {"action": "notes", "notes_text": "Decidimos lanzar en enero. Ana debe enviar la cotización el viernes. Hay que revisar el inventario."}, "expect": ["completed"]}])

skill("routine_automation",
  name="Rutinas", category="automatización",
  description="Rutinas configuradas y autorizadas por ti: una secuencia de habilidades con entradas fijas (p. ej. «buenos días»: resumen del día + tareas). Se ejecutan a petición; cada paso pasa por las mismas validaciones y las acciones externas piden confirmación.",
  exampleUtterances=["ejecuta la rutina de buenos días", "crea una rutina para empezar el día", "qué rutinas tengo", "borra la rutina 2"],
  inputSchema=O({"action": S(enum=["list", "create", "run", "delete"]), "name": S(80), "routine_id": S(20), "triggers": A(S(80), 5), "steps": A(O({"skill": S(64), "input": ANY_OBJ}, ["skill"]), 8)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  requiresInternet=False, cost="El de las habilidades que ejecute.", risk="external", limits={"timeoutSeconds": 300, "maxToolCalls": 0}, cancellation="Cancelable entre pasos y dentro de cada paso; los pasos ya completados se informan.",
  errorHandling="Paso inexistente o con entrada inválida → se rechaza al crear. Un paso fallido no detiene los siguientes salvo cancelación o falta de confirmación.",
  maturity="partial", missing=["disparadores automáticos por hora o ubicación (hoy se ejecutan a petición)"],
  privacy="Las rutinas se guardan en el teléfono.", localRoute=True,
  testCases=[{"name": "listar", "input": {"action": "list"}, "expect": ["completed"]}])

# ---------------------------------------------------------------- investigación
skill("business_prospecting",
  name="Prospección de negocios", category="negocio",
  description="Busca negocios reales en OpenStreetMap por categoría y zona de Costa Rica, los ordena por datos de contacto disponibles (cada dato con su estado) y guarda los mejores como prospectos.",
  exampleUtterances=["busca ferreterías en Heredia y guarda las 5 mejores como prospectos", "encuentra clientes potenciales: panaderías en Cartago", "prospecta restaurantes en Escazú con teléfono", "busca supermercados cerca de mí para venderles"],
  inputSchema=O({"category": S(80, 2), "location": S(120), "near_me": B(), "limit": I(1, 50), "save_top": I(0, 10), "note": S(300), "only_with_phone": B()}, ["category"]),
  outputSchema=OUT({"total": N(0), "ranked": A(ANY_OBJ), "saved": A(ANY_OBJ)}, ["ranked", "saved"]),
  tools=["business_search", "save_prospect"], optionalPermissions=["location"], requiresInternet=True, requiresExternalService=["jarvis_server"],
  cost="Sin costo de IA (OpenStreetMap es gratuito); usa tu servidor.", risk="local_write", limits={"timeoutSeconds": 90, "maxToolCalls": 12}, cancellation=CANCEL,
  errorHandling="Sin servidor → requiere configuración. Zona no encontrada → pide otra. Sin teléfono en la fuente → «no disponible», nunca inventado.",
  maturity="implemented", privacy="Solo la categoría y la zona (o tu ubicación aproximada si la pides) van al servidor.",
  testCases=[{"name": "sin_servidor", "input": {"category": "ferreterías", "location": "Heredia"}, "expect": ["needs_configuration", "completed"]}])

skill("deep_research",
  name="Investigación profunda", category="investigación",
  description="Investigación web en varias etapas: plan de consultas, búsqueda, lectura de las fuentes más relevantes (prioriza las oficiales), extracción de evidencias y conclusiones con citas [n]. El contenido web es dato, nunca instrucción.",
  exampleUtterances=["investiga a fondo el mercado de café de especialidad en Canadá", "haz una investigación sobre energía solar en Costa Rica con fuentes", "investiga qué dice la evidencia sobre el teletrabajo", "necesito un informe investigado sobre el precio del aguacate"],
  inputSchema=O({"question": S(500, 5), "depth": S(enum=["quick", "standard", "thorough"]), "domains": A(S(120), 10)}, ["question"]),
  outputSchema=OUT({"question": S(), "queries": A(S()), "sources": A(ANY_OBJ), "findings": A(ANY_OBJ), "used_model": B()}, ["question", "queries", "sources", "findings", "used_model"]),
  tools=["web_search", "fetch_page"], requiresInternet=True, requiresExternalService=["jarvis_server"], model="optional",
  cost="Búsquedas según tu proveedor (SearXNG gratis). Conclusiones con IA: tokens del modelo (modo profundo).", risk="read", limits={"timeoutSeconds": 240, "maxToolCalls": 14}, cancellation=CANCEL_RO,
  errorHandling="Sin proveedor de búsqueda o sin servidor → requiere configuración. Fuentes ilegibles → se omiten con aviso. Sin modelo → hallazgos sin conclusiones (parcial).",
  maturity="implemented", privacy="Solo la pregunta y las consultas viajan al servidor y al buscador.",
  testCases=[{"name": "sin_servidor", "input": {"question": "precio del café en Costa Rica"}, "expect": ["needs_configuration"]}])

skill("import_export_research",
  name="Investigación de importación y exportación", category="negocio",
  description="Para un producto: fabricantes y proveedores, precios publicados (con fuente, sin inventar), requisitos oficiales en Costa Rica (PROCOMER, Hacienda, Salud, SFE…), referencias arancelarias y estadísticas oficiales de comercio de UN Comtrade (con el código SA), con pasos sugeridos.",
  exampleUtterances=["investiga cómo importar bombillos led desde China", "quiero exportar piña a Estados Unidos, qué requisitos hay", "busca fabricantes y precios de bolsas biodegradables para importar", "requisitos para importar cosméticos a Costa Rica", "cuánto café exporta Costa Rica y a qué países"],
  inputSchema=O({"product": S(120, 2), "direction": S(enum=["import", "export"]), "country": S(60), "hs_code": S(pattern="^([0-9]{2}|[0-9]{4}|[0-9]{6})$"), "aspects": A(S(enum=["manufacturers", "suppliers", "prices", "requirements", "tariffs", "trade_stats"]), 6)}, ["product"]),
  outputSchema=OUT({"product": S(), "direction": S(), "sections": ANY_OBJ, "used_model": B()}, ["product", "direction", "sections", "used_model"]),
  tools=["web_search", "fetch_page", "trade_statistics"], requiresInternet=True, requiresExternalService=["jarvis_server"], model="optional",
  cost="Hasta ~10 búsquedas, 2 lecturas y 1 consulta a UN Comtrade (gratuita con cuota diaria); pasos con IA: tokens del modelo.", risk="read", limits={"timeoutSeconds": 240, "maxToolCalls": 15}, cancellation=CANCEL_RO,
  errorHandling="Sin fuentes oficiales → lo advierte y recomienda confirmar con la institución. Precios: solo los publicados, marcados como no cotizaciones. Sin código SA conocido o sin UN_COMTRADE_API_KEY en el servidor → sigue sin estadísticas y lo dice.",
  maturity="implemented", privacy="Solo el producto y los países viajan al buscador.",
  testCases=[{"name": "sin_servidor", "input": {"product": "bombillos LED", "direction": "import"}, "expect": ["needs_configuration"]}])

skill("price_comparison",
  name="Comparación de precios", category="compras",
  description="Busca precios publicados de un producto en tiendas en línea, extrae importe y moneda con su fuente y fecha, y compara mínimos, máximos y mediana por moneda (sin convertir monedas).",
  exampleUtterances=["compara precios de una freidora de aire", "dónde está más barato el iPhone 15 en Costa Rica", "busca el mejor precio de un taladro inalámbrico", "cuánto cuesta una silla ergonómica en tiendas"],
  inputSchema=O({"product": S(200, 2), "location": S(80), "max_sources": I(3, 10)}, ["product"]),
  outputSchema=OUT({"product": S(), "offers": A(OUT({"store": S(), "url": S(), "amount": N(0), "currency": S()}, ["store", "url", "amount", "currency"])), "stats": A(ANY_OBJ)}, ["product", "offers", "stats"]),
  tools=["web_search", "fetch_page"], requiresInternet=True, requiresExternalService=["jarvis_server"],
  cost="2 búsquedas y hasta 3 lecturas.", risk="read", limits={"timeoutSeconds": 120, "maxToolCalls": 6}, cancellation=CANCEL_RO,
  errorHandling="Sin precios en los resultados → lo dice y sugiere un nombre más específico. Monedas distintas → se comparan por separado.",
  maturity="implemented", privacy="Solo el nombre del producto viaja al buscador.",
  testCases=[{"name": "sin_servidor", "input": {"product": "freidora de aire"}, "expect": ["needs_configuration"]}])

# ---------------------------------------------------------------- comunicación
skill("message_drafter",
  name="Redactor de mensajes", category="comunicación",
  description="Redacta mensajes (WhatsApp, SMS, correo o genérico) según propósito y tono; los guarda como borrador y solo abre la app para enviarlos si lo pides y confirmas. Nunca envía solo.",
  exampleUtterances=["redacta un mensaje para confirmar la cita del jueves con Laura", "escríbele un mensaje cordial a mi cliente para agradecer la compra", "prepara un texto para avisar que llego tarde", "ayúdame a escribir un mensaje formal de disculpa"],
  inputSchema=O({"purpose": S(500, 2), "recipient": S(120), "channel": S(enum=["whatsapp", "sms", "email", "generic"]), "tone": S(enum=["cordial", "formal", "breve"]), "details": S(2000), "subject": S(200), "phone": S(30), "email": S(320), "send": B()}, ["purpose"]),
  outputSchema=OUT({"draft": OUT({"id": S(), "channel": S(), "body": S()}, ["id", "channel", "body"]), "opened": B(), "used_model": B()}, ["draft", "opened", "used_model"]),
  tools=["compose_message", "compose_email", "share_text"], requiresInternet=False, optionalServices=["jarvis_server"], model="optional",
  cost="Sin costo con plantillas; con IA, tokens del modelo.", risk="external", limits={"timeoutSeconds": 45, "maxToolCalls": 1}, cancellation=CANCEL,
  errorHandling="Abrir la app requiere confirmación; si no se confirma, queda el borrador. Falta el número/correo → se pide.",
  maturity="implemented", privacy="El propósito y los detalles solo van al servidor si hay IA configurada.",
  testCases=[{"name": "borrador", "input": {"purpose": "confirmar la cita del jueves", "recipient": "Laura", "channel": "whatsapp"}, "expect": ["completed"]}])

skill("email_assistant",
  name="Asistente de correo", category="comunicación",
  description="Redacta correos, responde correos que pegues (detecta preguntas y solicitudes), mejora la redacción, clasifica por prioridad correos pegados y abre el borrador en tu app de correo con confirmación. No lee tu bandeja.",
  exampleUtterances=["redacta un correo para pedir una cotización de 200 sacos", "ayúdame a responder este correo del banco", "mejora la redacción de este correo", "clasifica estos correos por prioridad"],
  inputSchema=O({"action": S(enum=["draft", "reply", "improve", "triage", "open_draft"]), "purpose": S(500), "notes": S(5000), "original_email": S(30000), "body": S(20000), "subject": S(200), "to": A(S(320), 10), "recipient_name": S(120), "tone": S(enum=["formal", "cordial", "breve"]), "emails_text": S(100000), "draft_id": S(20), "open": B()}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  tools=["compose_email"], requiresInternet=False, optionalServices=["jarvis_server"], model="optional",
  cost="Sin costo con plantillas; con IA, tokens del modelo.", risk="external", limits={"timeoutSeconds": 60, "maxToolCalls": 1}, cancellation=CANCEL,
  errorHandling="Abrir el correo pide confirmación. Sin IA → plantillas con marcadores [respuesta] para completar.",
  maturity="partial", missing=["leer la bandeja de entrada (requiere OAuth de Gmail/Outlook o IMAP; no configurado)", "enviar directamente (por diseño, el envío lo hace el usuario)"],
  privacy="Los correos pegados solo van al servidor si se pide ayuda de la IA.",
  testCases=[{"name": "borrador", "input": {"action": "draft", "purpose": "solicitar una cotización de 200 sacos de café", "to": ["compras@ejemplo.cr"]}, "expect": ["completed"]}])

skill("messaging_assistant",
  name="Asistente de mensajería", category="comunicación",
  description="Plantillas de mensajes de negocio (cobro amable, confirmación de cita, seguimiento de cotización…), borradores, apertura de WhatsApp o SMS con el texto listo (con confirmación) y recordatorios para enviarlos.",
  exampleUtterances=["manda un recordatorio de pago por WhatsApp a Carlos", "qué plantillas de mensajes tienes", "recuérdame mañana enviarle el mensaje a Sofía", "abre WhatsApp con un mensaje de seguimiento de cotización"],
  inputSchema=O({"action": S(enum=["templates", "draft", "open", "schedule_reminder"]), "channel": S(enum=["whatsapp", "sms"]), "phone": S(30), "contact_name": S(80), "text": S(4000), "template": S(enum=["recordatorio_pago", "confirmacion_cita", "seguimiento_cotizacion", "agradecimiento", "cobro_amable"]), "detail": S(300), "when": S(80)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  tools=["compose_message", "create_task"], requiresInternet=False, cost="Sin costo.", risk="external", limits={"timeoutSeconds": 30, "maxToolCalls": 1}, cancellation=CANCEL,
  errorHandling="WhatsApp sin número → lo pide. Abrir la app requiere confirmación.",
  maturity="partial", missing=["leer o enviar mensajes automáticamente (Android y WhatsApp no lo permiten a otras apps)"],
  privacy="Nada sale del teléfono hasta que tú envías el mensaje.",
  testCases=[{"name": "plantilla", "input": {"action": "draft", "channel": "whatsapp", "template": "recordatorio_pago", "contact_name": "Carlos", "detail": "de la factura 102"}, "expect": ["completed"]}])

skill("social_media_manager",
  name="Gestor de redes sociales", category="marketing",
  description="Redacta publicaciones adaptadas a cada red (límites de caracteres y hashtags), crea un calendario de contenido, programa recordatorios para publicar y abre el menú de compartir. No publica por sí mismo.",
  exampleUtterances=["escribe una publicación para Instagram sobre el nuevo café de temporada", "haz un calendario de contenido para dos semanas", "adapta este texto para X y LinkedIn", "recuérdame publicar el viernes a las 6 pm"],
  inputSchema=O({"action": S(enum=["draft_post", "adapt", "calendar", "schedule", "share"]), "topic": S(300), "text": S(5000), "platforms": A(S(enum=["instagram", "facebook", "x", "linkedin", "tiktok", "whatsapp_status"]), 6), "tone": S(60), "hashtags": B(), "call_to_action": S(120), "days": I(1, 31), "posts_per_week": I(1, 7), "start_date": S(10), "date": S(80)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  tools=["create_task", "share_text"], requiresInternet=False, optionalServices=["jarvis_server"], model="optional",
  cost="Sin costo; redacción con IA usa tokens.", risk="external", limits={"timeoutSeconds": 45, "maxToolCalls": 1}, cancellation=CANCEL,
  errorHandling="Texto demasiado largo para una red → se recorta y se informa. Compartir pide confirmación.",
  maturity="partial", missing=["publicar directamente (requiere conectar cada red con su API, permisos y revisión de la app)", "métricas de las publicaciones"],
  privacy="Nada se publica sin que tú lo hagas.",
  testCases=[{"name": "post", "input": {"action": "draft_post", "text": "Llegó nuestro café de temporada, tostado esta semana en Heredia. Pídelo hoy por WhatsApp.", "platforms": ["instagram", "x"]}, "expect": ["completed"]}])

skill("video_clips",
  name="Clips de video y YouTube", category="marketing",
  description="Recorta cualquier video público en clips cortos con OpusClip, te avisa del estado, los lista numerados y publica en YouTube el que elijas (privado por defecto) a través de tu cuenta conectada en OpusClip, con confirmación. También programa publicaciones.",
  exampleUtterances=["haz clips de este video https://youtu.be/abc123", "ya están mis clips", "sube el clip 2 a youtube", "programa el clip 1 en youtube para mañana a las 6 pm", "recorta este video en clips verticales"],
  inputSchema=O({"action": S(enum=["create", "status", "publish", "list"]), "video_url": S(2000, 8), "prompt": S(500), "language": S(pattern="^(auto|[a-z]{2})$"), "aspect": S(enum=["portrait", "landscape", "square"]),
                 "project_id": S(80, 3), "clip": I(1, 50), "title": S(100, 1), "description": S(4500), "privacy": S(enum=["private", "unlisted", "public"]), "publish_at": S(40)}, ["action"]),
  outputSchema=OUT({"action": S(), "project_id": S(), "stage": S(), "clips": A(ANY_OBJ), "post": ANY_OBJ, "projects": A(ANY_OBJ)}, ["action"]),
  tools=["clips_create", "clips_status", "clips_publish"], requiresInternet=True, requiresExternalService=["jarvis_server"],
  cost="Créditos de tu plan de OpusClip por cada video recortado (la API requiere plan Pro, Max o Business). Publicar en YouTube no gasta créditos según el conector usado.",
  risk="sensitive", limits={"timeoutSeconds": 90, "maxToolCalls": 3}, cancellation=CANCEL,
  errorHandling="Sin OPUSCLIP_API_KEY → requiere configuración. Video privado o enlace inválido → lo dice. Clips aún en proceso → indica la etapa y que vuelvas a preguntar. Sin YouTube conectado en OpusClip → explica cómo conectarlo. Publicar siempre pide confirmación.",
  maturity="partial", missing=["contrato de la API de OpusClip tomado de un conector de terceros (la documentación oficial no fue accesible): confirmar con la primera prueba real", "aviso automático cuando los clips están listos (hoy se pregunta a JARVIS)"],
  privacy="El enlace del video va a OpusClip; los datos de YouTube los maneja OpusClip. JARVIS no ve credenciales de Google. Publica solo contenido propio o con permiso.", localRoute=True,
  testCases=[{"name": "sin_servidor", "input": {"action": "create", "video_url": "https://youtu.be/abc123"}, "expect": ["needs_configuration"]}])

skill("marketing_optimizer",
  name="Optimizador de marketing", category="marketing",
  description="Puntúa textos publicitarios (llamada a la acción, beneficios, cifras, urgencia, legibilidad), propone variantes A/B, lista de verificación de campañas y enlaces con parámetros UTM.",
  exampleUtterances=["analiza este anuncio", "dame variantes A/B de este texto publicitario", "lista de verificación para una campaña en Facebook", "crea un enlace con UTM para mi campaña de Instagram"],
  inputSchema=O({"action": S(enum=["analyze_copy", "ab_variants", "campaign_checklist", "utm"]), "text": S(5000), "channel": S(80), "url": S(2000), "source": S(60), "medium": S(60), "campaign": S(80), "content": S(80)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  requiresInternet=False, optionalServices=["jarvis_server"], model="optional", cost="Sin costo; variantes con IA usan tokens.",
  risk="read", limits={"timeoutSeconds": 45, "maxToolCalls": 0}, cancellation=CANCEL_RO,
  errorHandling="URL inválida o parámetros UTM faltantes → se indican. Sin IA → variantes por reglas.",
  maturity="implemented", privacy="El texto solo va al servidor si se piden variantes con IA.",
  testCases=[{"name": "utm", "input": {"action": "utm", "url": "https://micafe.cr/promo", "source": "instagram", "campaign": "cafe temporada"}, "expect": ["completed"]}])

skill("website_builder",
  name="Creador de sitios web", category="marketing",
  description="Genera un sitio web de una página, adaptable al móvil, para tu negocio (servicios, contacto por WhatsApp/llamada/correo, horario, mapa de OpenStreetMap y datos estructurados schema.org) y lo guarda como HTML.",
  exampleUtterances=["hazme una página web para mi panadería", "crea un sitio web sencillo para mi taller", "genera una landing page con mi WhatsApp", "quiero una web de una página para mi negocio"],
  inputSchema=O({"business_name": S(120, 2), "description": S(600), "services": A(S(120), 20), "phone": S(30), "whatsapp": S(30), "email": S(320), "address": S(300), "hours": S(200), "color": S(7), "lat": N(-90, 90), "lon": N(-180, 180), "save": B()}, ["business_name"]),
  outputSchema=OUT({"business_name": S(), "html_bytes": I(0), "preview": S()}, ["business_name", "html_bytes", "preview"]),
  tools=["save_document"], requiresInternet=False, cost="Sin costo.", risk="local_write", limits={"timeoutSeconds": 20, "maxToolCalls": 1}, cancellation=CANCEL,
  errorHandling="Sin medio de contacto → se pide. Todo el texto se escapa (sin inyección de HTML).",
  maturity="partial", missing=["publicación automática en un hosting (se entrega el archivo HTML)"],
  privacy="Se genera en el teléfono.",
  testCases=[{"name": "sitio", "input": {"business_name": "Panadería La Espiga", "description": "Pan artesanal en Heredia", "services": ["Pan dulce", "Queques"], "whatsapp": "50688887777"}, "expect": ["partial"]}])

# ---------------------------------------------------------------- finanzas
CUR = S(enum=["CRC", "USD", "EUR"])
skill("expense_capture",
  name="Registro de gastos e ingresos", category="finanzas",
  description="Registra gastos e ingresos dichos en lenguaje natural («gasté ₡5 000 en gasolina ayer»), con categoría automática y aviso de presupuesto; también desde la foto de un recibo (con el servidor) tras tu confirmación.",
  exampleUtterances=["gasté 5000 colones en gasolina", "anota que pagué 20 dólares de almuerzo ayer", "registra un ingreso de 150 mil colones por una venta", "lee este recibo y regístralo como gasto"],
  inputSchema=O({"action": S(enum=["add_from_text", "add", "from_receipt", "list", "delete"]), "text": S(500), "amount": N(0, 1e12), "currency": CUR, "category": S(60), "description": S(200), "date": S(40), "type": S(enum=["expense", "income"]), "period": S(enum=["week", "month", "last_month", "year", "all"]), "transaction_id": S(20)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  tools=["analyze_image"], requiresInternet=False, optionalServices=["jarvis_server"], model="none",
  cost="Sin costo; leer recibos usa el modelo de visión del servidor.", risk="sensitive", limits={"timeoutSeconds": 90, "maxToolCalls": 1}, cancellation=CANCEL,
  errorHandling="Sin monto o sin moneda → pide el dato (nunca asume la moneda). Recibo ilegible → pide registrarlo por voz. Borrar pide confirmación.",
  maturity="implemented", privacy="Datos financieros solo en el teléfono. La foto del recibo solo va al servidor si usas esa opción.", localRoute=True,
  testCases=[{"name": "texto", "input": {"action": "add_from_text", "text": "gasté 5000 colones en gasolina ayer"}, "expect": ["completed"]}])

skill("personal_finance",
  name="Finanzas personales", category="finanzas",
  description="Resumen de ingresos, gastos y balance por moneda y categoría, presupuestos mensuales con alertas al 80 %, mayores gastos y comparación con el mes anterior, a partir de tus movimientos registrados.",
  exampleUtterances=["cuánto he gastado este mes", "pon un presupuesto de comida de 150 mil colones", "cómo van mis finanzas", "compara mis gastos con el mes pasado"],
  inputSchema=O({"action": S(enum=["summary", "set_budget", "budgets", "top_expenses", "compare_months"]), "period": S(enum=["week", "month", "last_month", "year", "all"]), "category": S(60), "limit": N(0, 1e12), "currency": CUR}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  requiresInternet=False, cost="Sin costo.", risk="local_write", limits={"timeoutSeconds": 20, "maxToolCalls": 0}, cancellation=CANCEL,
  errorHandling="Sin movimientos → lo dice. Monedas distintas se resumen por separado (sin convertir).",
  maturity="implemented", privacy="Todo en el teléfono.", localRoute=True,
  testCases=[{"name": "resumen", "input": {"action": "summary", "period": "month"}, "expect": ["completed"]}])

skill("cashflow_forecast",
  name="Proyección de flujo de caja", category="finanzas",
  description="Proyecta tu saldo de 1 a 12 meses con ingresos y gastos fijos, el promedio de gasto variable y las cotizaciones aceptadas, y avisa en qué mes el saldo sería negativo. Las suposiciones se informan.",
  exampleUtterances=["proyecta mi flujo de caja de los próximos 3 meses", "agrega el alquiler de 300 mil como gasto fijo el día 1", "me va a alcanzar el dinero hasta diciembre", "lista mis gastos fijos"],
  inputSchema=O({"action": S(enum=["forecast", "add_recurring", "list_recurring", "delete_recurring"]), "months": I(1, 12), "starting_balance": N(-1e12, 1e12), "currency": CUR, "name": S(80), "amount": N(0, 1e12), "type": S(enum=["expense", "income"]), "day_of_month": I(1, 31), "recurring_id": S(20)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  requiresInternet=False, cost="Sin costo.", risk="sensitive", limits={"timeoutSeconds": 20, "maxToolCalls": 0}, cancellation=CANCEL,
  errorHandling="Sin saldo inicial → lo estima con tus movimientos y lo advierte. Borrar pide confirmación.",
  maturity="implemented", privacy="Todo en el teléfono.",
  testCases=[{"name": "proyeccion", "input": {"action": "forecast", "months": 3, "starting_balance": 500000, "currency": "CRC"}, "expect": ["completed"]}])

skill("invoice_quote_creator",
  name="Cotizaciones y proformas", category="finanzas",
  description="Crea cotizaciones y facturas proforma numeradas con líneas, IVA (13 % por defecto) y totales exactos, genera el documento HTML, cambia su estado, registra el pago como ingreso y abre el correo para enviarla (con confirmación).",
  exampleUtterances=["haz una cotización para Café La Montaña de 10 sacos a 45 mil cada uno", "crea una proforma para el cliente Ana Mora", "marca como pagada la cotización 2", "envía la cotización 1 por correo"],
  inputSchema=O({"action": S(enum=["create", "list", "update_status", "record_payment", "send"]), "kind": S(enum=["quote", "proforma"]), "client": S(160), "client_email": S(320), "issuer": S(160),
                 "lines": A(O({"description": S(300, 1), "quantity": N(0), "unit_price": N(0)}, ["description", "quantity", "unit_price"]), 100), "tax_rate": N(0, 100), "currency": CUR, "valid_days": I(1, 365), "notes": S(1000), "quote_id": S(20), "status": S(enum=["draft", "sent", "accepted", "rejected", "paid"]), "save": B()}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  tools=["save_document", "compose_email"], requiresInternet=False, cost="Sin costo.", risk="external", limits={"timeoutSeconds": 30, "maxToolCalls": 2}, cancellation=CANCEL,
  errorHandling="Líneas incompletas → se indican. Pago ya registrado → no se duplica. Enviar requiere confirmación.",
  maturity="partial", missing=["factura electrónica oficial de Hacienda (requiere firma digital y la API de comprobantes electrónicos)", "adjuntar el PDF al correo (se adjunta el HTML a mano)"],
  privacy="Documentos y datos de clientes solo en el teléfono.",
  testCases=[{"name": "cotizacion", "input": {"action": "create", "client": "Café La Montaña", "lines": [{"description": "Saco de café 46 kg", "quantity": 10, "unit_price": 45000}], "save": False}, "expect": ["completed"]}])

# ---------------------------------------------------------------- negocio
skill("customer_relationships",
  name="Relación con clientes (CRM)", category="negocio",
  description="CRM sobre tus prospectos y contactos: registra llamadas, reuniones y mensajes, programa seguimientos con recordatorio, muestra el historial, cambia el estado y lista quién lleva días sin contacto.",
  exampleUtterances=["registra que llamé a Ferretería El Clavo y quedé de enviar precios", "historial con Súper Uno", "a quién no he contactado en dos semanas", "marca a Café Tres como cliente"],
  inputSchema=O({"action": S(enum=["log_interaction", "history", "follow_ups", "set_status", "contacts"]), "prospect_id": I(1), "contact_name": S(160), "kind": S(enum=["llamada", "reunión", "correo", "mensaje", "visita", "nota"]), "summary": S(2000), "follow_up": S(300), "follow_up_when": S(80), "status": S(enum=["NUEVO", "CONTACTADO", "INTERESADO", "NEGOCIANDO", "CLIENTE", "DESCARTADO"]), "days": I(1, 365), "query": S(120)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  tools=["create_task", "update_prospect"], requiresInternet=False, cost="Sin costo.", risk="local_write", limits={"timeoutSeconds": 20, "maxToolCalls": 2}, cancellation=CANCEL,
  errorHandling="Contacto no guardado como prospecto → se registra como contacto libre y se avisa. Nombre ambiguo → pide el id.",
  maturity="implemented", privacy="Todo en el teléfono.",
  testCases=[{"name": "contactos", "input": {"action": "contacts"}, "expect": ["completed"]}])

skill("negotiation_coach",
  name="Entrenador de negociación", category="negocio",
  description="Prepara negociaciones con objetivo, límite (BATNA), apertura, concesiones decrecientes y zona de acuerdo; evalúa ofertas (aceptar, contraofertar o rechazar) y entrena respuestas a tácticas frecuentes.",
  exampleUtterances=["ayúdame a preparar una negociación: quiero vender a 50 mil y mi mínimo es 42 mil", "me ofrecieron 44 mil, qué hago", "cómo respondo si me dicen que es su última oferta", "plan de negociación como comprador"],
  inputSchema=O({"action": S(enum=["plan", "evaluate_offer", "practice"]), "role": S(enum=["seller", "buyer"]), "target": N(0), "walk_away": N(0), "opening": N(0), "offer": N(0), "their_limit": N(0), "currency": CUR, "issues": A(S(80), 10), "context": S(1000)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  requiresInternet=False, optionalServices=["jarvis_server"], model="optional", cost="Sin costo; la práctica con IA usa tokens.", risk="read",
  limits={"timeoutSeconds": 45, "maxToolCalls": 0}, cancellation=CANCEL_RO,
  errorHandling="Límite incoherente con el objetivo → se explica. Sin zona de acuerdo → recomienda preparar la alternativa.",
  maturity="implemented", privacy="Las cifras solo salen del teléfono si pides práctica con IA.",
  testCases=[{"name": "plan", "input": {"action": "plan", "role": "seller", "target": 50000, "walk_away": 42000, "currency": "CRC"}, "expect": ["completed"]}])

skill("contract_review",
  name="Revisión de contratos", category="negocio",
  description="Revisión orientativa de contratos en español: identifica cláusulas (plazo, renovación, terminación, penalidades, pagos, exclusividad, jurisdicción…), señala riesgos y cláusulas ausentes, extrae montos y fechas y, si lo pides, lo explica con IA. No es asesoría legal.",
  exampleUtterances=["revisa este contrato de alquiler", "qué riesgos tiene este contrato", "busca cláusulas de renovación automática y penalidades", "analiza el contrato de servicios que te voy a dar"],
  inputSchema=O({"source": S(enum=["text", "file"]), "text": S(400000), "party": S(120), "question": S(500), "use_ai": B()}, ["source"]),
  outputSchema=OUT({"clauses": A(ANY_OBJ), "risks": A(ANY_OBJ), "missing": A(S()), "used_model": B()}, ["clauses", "risks", "missing", "used_model"]),
  requiresInternet=False, optionalServices=["jarvis_server"], model="optional", cost="Sin costo; el análisis con IA usa tokens (modo profundo).", risk="read",
  limits={"timeoutSeconds": 120, "maxToolCalls": 0}, cancellation=CANCEL_RO,
  errorHandling="PDF no soportado → pide DOCX o texto. Siempre recuerda que no sustituye a un abogado.",
  maturity="implemented", privacy="El contrato se analiza en el teléfono; solo va al servidor si activas use_ai.",
  testCases=[{"name": "texto", "input": {"source": "text", "text": "El presente contrato tendrá una duración de un año y se renovará automáticamente por periodos iguales. En caso de terminación anticipada, el arrendatario pagará una multa de dos meses de renta. Las partes se someten a los tribunales de Miami, Florida."}, "expect": ["completed"]}])

skill("competitor_intelligence",
  name="Inteligencia competitiva", category="negocio",
  description="Analiza la competencia de tu categoría en una zona con OpenStreetMap: cuántos hay, dónde se concentran, qué porcentaje publica teléfono o sitio web y, leyendo algunos sitios, qué ofrecen y precios publicados.",
  exampleUtterances=["analiza mi competencia: panaderías en San Pedro", "cuántas ferreterías hay en Heredia y cuántas tienen página web", "estudio de competidores de cafeterías en Escazú", "qué tan saturado está el mercado de veterinarias en Cartago"],
  inputSchema=O({"category": S(80, 2), "location": S(120), "own_business_name": S(120), "read_websites": I(0, 5)}, ["category"]),
  outputSchema=OUT({"competitors": N(0), "insights": A(S())}, ["competitors", "insights"]),
  tools=["business_search", "fetch_page"], requiresInternet=True, requiresExternalService=["jarvis_server"], cost="1 búsqueda de negocios y hasta 5 lecturas.", risk="read",
  limits={"timeoutSeconds": 150, "maxToolCalls": 7}, cancellation=CANCEL_RO,
  errorHandling="Sin servidor → requiere configuración. Sitios ilegibles → se omiten con aviso. Cobertura de OSM incompleta → se advierte.",
  maturity="implemented", privacy="Solo categoría y zona viajan al servidor.",
  testCases=[{"name": "sin_servidor", "input": {"category": "panaderías", "location": "San Pedro"}, "expect": ["needs_configuration"]}])

skill("supplier_verifier",
  name="Verificador de proveedores", category="negocio",
  description="Revisa señales de confianza de un proveedor: correo gratuito o del dominio, código de país del teléfono, formato de cédula jurídica, datos publicados en su sitio y menciones de fraude en la web, con puntuación orientativa y recomendaciones.",
  exampleUtterances=["verifica este proveedor de China antes de pagarle", "es confiable el proveedor Shenzhen LED Co", "revisa si este correo y teléfono de proveedor son coherentes", "verifica la empresa Distribuidora XYZ"],
  inputSchema=O({"name": S(160, 2), "website": S(500), "email": S(320), "phone": S(40), "country": S(60), "tax_id": S(30)}, ["name"]),
  outputSchema=OUT({"name": S(), "score": N(0, 100), "verdict": S(), "signals": A(ANY_OBJ)}, ["name", "score", "verdict", "signals"]),
  tools=["fetch_page", "web_search"], requiresInternet=False, optionalServices=["jarvis_server"], cost="Sin servidor: sin costo. Con servidor: 1 búsqueda y 1 lectura.", risk="read",
  limits={"timeoutSeconds": 90, "maxToolCalls": 2}, cancellation=CANCEL_RO,
  errorHandling="Sin servidor → solo comprobaciones de formato y coherencia (parcial). Resultados negativos se presentan como señales para revisar, no como prueba.",
  maturity="implemented", privacy="Nombre, sitio y datos del proveedor van al buscador solo si hay servidor.",
  testCases=[{"name": "formatos", "input": {"name": "Shenzhen LED Co", "email": "ventas.led@gmail.com", "phone": "+86 755 1234 5678", "country": "China", "website": "https://shenzhen-led.example"}, "expect": ["partial", "completed"]}])

# ---------------------------------------------------------------- planificación
skill("schedule_optimizer",
  name="Optimizador de agenda", category="asistente",
  description="Organiza tu jornada: toma los eventos del calendario y las tareas fijas, calcula los huecos libres y ubica las tareas pendientes por urgencia y prioridad (estima duraciones «30 min», «2 h»), con márgenes entre bloques.",
  exampleUtterances=["organiza mi día", "acomoda mis tareas de mañana en la agenda", "planifica mi día de 8 a 5", "optimiza mi agenda de hoy"],
  inputSchema=O({"date": S(10), "work_start": S(5), "work_end": S(5), "default_task_minutes": I(10, 480), "buffer_minutes": I(0, 60), "include_overdue": B(), "include_undated": B()}),
  outputSchema=OUT({"date": S(), "blocks": A(OUT({"start": S(), "end": S(), "type": S(), "title": S()}, ["start", "end", "type", "title"])), "unscheduled": A(S()), "utilization_percent": N(0, 100)}, ["date", "blocks", "unscheduled", "utilization_percent"]),
  optionalPermissions=["calendar.read"], requiresInternet=False, cost="Sin costo.", risk="read", limits={"timeoutSeconds": 20, "maxToolCalls": 0}, cancellation=CANCEL_RO,
  errorHandling="Sin permiso de calendario → plan parcial con aviso. Tareas que no caben → se listan.",
  maturity="implemented", privacy="Todo en el teléfono.", localRoute=True,
  testCases=[{"name": "manana", "input": {"date": "tomorrow"}, "expect": ["completed", "partial"]}])

skill("renewal_monitor",
  name="Monitor de renovaciones", category="asistente",
  description="Registra vencimientos (marchamo, licencia, pasaporte, seguros, suscripciones, dominios…) y programa un aviso días antes como tarea con recordatorio; lista lo que vence pronto.",
  exampleUtterances=["avísame 15 días antes de que venza mi licencia el 10 de marzo", "qué vence pronto", "registra el vencimiento del pasaporte el 2026-05-20", "recuérdame renovar el marchamo"],
  inputSchema=O({"action": S(enum=["add", "list", "upcoming", "delete"]), "name": S(120), "kind": S(enum=["documento", "vehículo", "suscripción", "seguro", "servicio", "licencia", "otro"]), "expires_on": S(40), "remind_days_before": I(1, 120), "notes": S(300), "renewal_id": S(20), "days": I(1, 730)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  tools=["create_task", "delete_task"], requiresInternet=False, cost="Sin costo.", risk="sensitive", limits={"timeoutSeconds": 20, "maxToolCalls": 1}, cancellation=CANCEL,
  errorHandling="Fecha no entendida → pide AAAA-MM-DD. Aviso en el pasado → se programa para mañana con aviso. Borrar pide confirmación.",
  maturity="implemented", privacy="Todo en el teléfono.", localRoute=True,
  testCases=[{"name": "marchamo", "input": {"action": "add", "name": "Marchamo", "kind": "vehículo"}, "expect": ["completed"]}])

skill("travel_planner",
  name="Planificador de viajes", category="asistente",
  description="Planifica un viaje: ubica el destino, trae el pronóstico diario (hasta 16 días), arma un itinerario base, lista de equipaje según clima y propósito, reparto de presupuesto y, si es al extranjero, busca requisitos de entrada (con el servidor).",
  exampleUtterances=["planifica mi viaje a Monteverde del 10 al 12 de octubre", "voy a Panamá por negocios la próxima semana, qué llevo", "arma un itinerario para Guanacaste con playa y comida", "qué clima hará en mi viaje a Puerto Viejo"],
  inputSchema=O({"destination": S(120, 2), "start_date": S(10), "end_date": S(10), "purpose": S(enum=["ocio", "negocios"]), "interests": A(S(60), 8), "budget": N(0), "currency": CUR, "create_tasks": B()}, ["destination", "start_date", "end_date"]),
  outputSchema=OUT({"destination": S(), "itinerary": A(ANY_OBJ), "packing": A(S())}, ["destination", "itinerary", "packing"]),
  tools=["find_place", "web_search", "create_task"], requiresInternet=True, requiresExternalService=["open_meteo"], optionalServices=["jarvis_server"], cost="Sin costo (Open-Meteo); requisitos de entrada: 1 búsqueda.", risk="local_write",
  limits={"timeoutSeconds": 90, "maxToolCalls": 5}, cancellation=CANCEL,
  errorHandling="Destino no encontrado o sin Internet → plan parcial sin clima. Fechas lejanas → sin pronóstico, con aviso.",
  maturity="implemented", privacy="Solo el destino viaja al geocodificador y al pronóstico.",
  testCases=[{"name": "sin_red", "input": {"destination": "Monteverde", "start_date": "2025-10-10", "end_date": "2025-10-12"}, "expect": ["partial", "completed"]}])

skill("meal_and_grocery_planner",
  name="Comidas y compras", category="hogar",
  description="Plan semanal de comidas con recetas costarricenses sencillas (respeta vegetariano, vegano, sin gluten, económico), lista de compras con cantidades según personas y lista del súper para marcar.",
  exampleUtterances=["planifica las comidas de la semana para 4 personas", "haz la lista de compras del menú", "agrega leche a la lista del súper", "qué me falta comprar"],
  inputSchema=O({"action": S(enum=["plan_week", "grocery_list", "add_item", "check_item", "list_groceries", "clear_checked"]), "people": I(1, 12), "preferences": A(S(enum=["vegetariano", "vegano", "sin_gluten", "economico"]), 4), "meals_per_day": A(S(enum=["desayuno", "almuerzo", "cena"]), 3), "meals": A(S(120), 21), "item": S(120), "quantity": S(40), "item_id": S(20)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  requiresInternet=False, cost="Sin costo.", risk="local_write", limits={"timeoutSeconds": 20, "maxToolCalls": 0}, cancellation=CANCEL,
  errorHandling="Sin recetas para las preferencias → pide tus platos. Platos propios sin receta → se avisan para añadir ingredientes a mano.",
  maturity="implemented", privacy="Todo en el teléfono.", localRoute=True,
  testCases=[{"name": "plan", "input": {"action": "plan_week", "people": 2, "preferences": ["vegetariano"]}, "expect": ["completed"]}])

# ---------------------------------------------------------------- aprendizaje
skill("personal_tutor",
  name="Tutor personal", category="aprendizaje",
  description="Tarjetas de estudio con repetición espaciada (SM-2): las crea de tus apuntes («término: definición») o de un tema (con IA), te pregunta las que tocan hoy, califica tus respuestas, hace cuestionarios de opción múltiple y explica temas con el servidor.",
  exampleUtterances=["crea tarjetas de estudio con estos apuntes", "quiero estudiar las tarjetas de hoy", "hazme un cuestionario de biología", "explícame la fotosíntesis"],
  inputSchema=O({"action": S(enum=["create_cards", "study", "answer", "quiz", "explain", "progress"]), "deck": S(80), "topic": S(200), "level": S(40), "text": S(50000), "cards": A(O({"front": S(500, 1), "back": S(1000, 1)}, ["front", "back"]), 200), "card_id": S(20), "grade": S(20), "answer": S(1000), "questions": I(1, 20), "question": S(500)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  requiresInternet=False, optionalServices=["jarvis_server"], model="optional", cost="Sin costo; crear tarjetas por tema o explicar usa tokens.", risk="local_write",
  limits={"timeoutSeconds": 60, "maxToolCalls": 0}, cancellation=CANCEL,
  errorHandling="Menos de 4 tarjetas → no hay cuestionario. Explicar sin servidor → parcial con alternativa.",
  maturity="implemented", privacy="Tarjetas en el teléfono; el tema solo va al servidor si pides IA.",
  testCases=[{"name": "apuntes", "input": {"action": "create_cards", "deck": "biología", "text": "Célula: unidad básica de la vida\nMitocondria: orgánulo que produce energía\nADN: molécula que contiene la información genética"}, "expect": ["completed"]}])

skill("language_coach",
  name="Entrenador de idiomas", category="aprendizaje",
  description="Vocabulario con repetición espaciada por idioma, libro de frases útiles (inglés, portugués, francés), corrección de oraciones en inglés con reglas para hispanohablantes y corrección completa con IA.",
  exampleUtterances=["corrige esta oración en inglés: i have 30 years", "agrega estas palabras de inglés a mi vocabulario", "frases útiles en portugués para viajar", "practiquemos vocabulario de francés"],
  inputSchema=O({"action": S(enum=["add_words", "study", "answer", "progress", "phrasebook", "check_sentence"]), "language": S(enum=["en", "pt", "fr", "it", "de"]), "words": A(O({"word": S(120, 1), "translation": S(200, 1), "example": S(300)}, ["word", "translation"]), 200), "sentence": S(1000), "card_id": S(20), "grade": S(20), "answer": S(500)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  requiresInternet=False, optionalServices=["jarvis_server"], model="optional", cost="Sin costo; corrección con IA usa tokens.", risk="local_write",
  limits={"timeoutSeconds": 45, "maxToolCalls": 0}, cancellation=CANCEL,
  errorHandling="Idioma sin reglas locales → corrección solo con IA (parcial si no hay servidor).",
  maturity="implemented", privacy="Vocabulario en el teléfono.",
  testCases=[{"name": "oracion", "input": {"action": "check_sentence", "language": "en", "sentence": "i have 30 years and people is nice"}, "expect": ["completed", "partial"]}])

# ---------------------------------------------------------------- medios
skill("voice_memo_inbox",
  name="Bandeja de notas de voz", category="productividad",
  description="Captura ideas y pendientes al vuelo (texto dictado o grabación transcrita por el servidor), sugiere si son tarea, recuerdo, gasto o idea y los convierte cuando lo pides, sin duplicar.",
  exampleUtterances=["guarda esta idea: hacer una promoción de café en diciembre", "qué notas tengo en la bandeja", "convierte la nota 2 en tarea", "graba una nota de voz"],
  inputSchema=O({"action": S(enum=["capture_text", "record", "list", "process", "convert", "archive"]), "text": S(5000), "source": S(40), "item_id": S(20), "to": S(enum=["task", "memory", "expense", "note"]), "max_seconds": I(5, 300)}, ["action"]),
  outputSchema=OUT({"action": S()}, ["action"]),
  tools=["transcribe_audio", "create_task", "remember", "save_document"], optionalPermissions=["microphone"], requiresInternet=False, optionalServices=["jarvis_server"],
  cost="Sin costo para texto; grabar y transcribir usa el modelo de transcripción del servidor.", risk="local_write", limits={"timeoutSeconds": 180, "maxToolCalls": 2}, cancellation=CANCEL,
  errorHandling="Nota ya convertida → no se duplica. Transcripción vacía → pide grabar de nuevo. Sin servidor → grabar requiere configuración; el texto funciona igual.",
  maturity="implemented", privacy="Notas en el teléfono; el audio solo va al servidor si grabas para transcribir.", localRoute=True,
  testCases=[{"name": "captura", "input": {"action": "capture_text", "text": "Tengo que llamar al contador mañana a las 10"}, "expect": ["completed"]}])

skill("visual_scene_assistant",
  name="Asistente visual", category="medios",
  description="Describe lo que muestra una foto (de la cámara o de un archivo) o lee el texto que contiene, con el modelo de visión del servidor. La imagen no se guarda.",
  exampleUtterances=["qué ves en esta foto", "lee el texto de este letrero", "describe la imagen que te voy a mostrar", "qué dice este documento en la foto"],
  inputSchema=O({"source": S(enum=["camera", "file"]), "task": S(enum=["describe", "read_text"]), "question": S(500)}),
  outputSchema=OUT({"task": S(), "text": S()}, ["task", "text"]),
  tools=["analyze_image"], requiresInternet=True, requiresExternalService=["jarvis_server"], model="required", cost="Tokens de imagen del modelo de visión (centavos por imagen).", risk="read",
  limits={"timeoutSeconds": 120, "maxToolCalls": 1}, cancellation=CANCEL_RO,
  errorHandling="Sin servidor → requiere configuración. Foto cancelada → no hace nada. Descripción con avisos de posibles errores.",
  maturity="implemented", privacy="La imagen se envía al servidor y a OpenAI solo cuando la tomas o eliges para esta función; no se almacena.",
  testCases=[{"name": "sin_servidor", "input": {"source": "camera", "task": "describe"}, "expect": ["needs_configuration"]}])

skill("barcode_business_card_scanner",
  name="Escáner de códigos y tarjetas", category="medios",
  description="Lee códigos de barras y QR en el teléfono (ZXing): busca el producto en Open Food Facts y puede sumarlo al inventario; lee tarjetas de presentación (QR vCard en el teléfono o foto con el servidor) y guarda el contacto tras tu confirmación.",
  exampleUtterances=["escanea este código de barras", "lee esta tarjeta de presentación y guarda el contacto", "escanea el producto y súmalo al inventario", "qué producto es este código"],
  inputSchema=O({"mode": S(enum=["barcode", "business_card"]), "source": S(enum=["camera", "file"]), "add_to_inventory": B(), "quantity": N(0, 1e6), "name": S(120), "save_contact": B()}, ["mode"]),
  outputSchema=OUT({"mode": S()}, ["mode"]),
  tools=["lookup_product", "analyze_image"], requiresInternet=False, optionalServices=["open_food_facts", "jarvis_server"], cost="Códigos: sin costo. Tarjetas por foto: tokens de visión.", risk="local_write",
  limits={"timeoutSeconds": 120, "maxToolCalls": 2}, cancellation=CANCEL,
  errorHandling="Código ilegible → consejos de encuadre. Producto ausente en Open Food Facts → se dice. Tarjeta leída por IA → se pide confirmación antes de guardar.",
  maturity="implemented", privacy="Los códigos se leen en el teléfono; solo las fotos de tarjetas sin QR van al servidor.",
  testCases=[{"name": "sin_imagen", "input": {"mode": "barcode", "source": "camera"}, "expect": ["cancelled"]}])

def main(outdir):
    os.makedirs(outdir, exist_ok=True)
    order = ["id", "name", "description", "category", "exampleUtterances", "inputSchema", "outputSchema", "tools", "requiredPermissions",
             "optionalPermissions", "requiresInternet", "requiresExternalService", "optionalServices", "model", "cost", "risk", "limits",
             "cancellation", "errorHandling", "maturity", "missing", "privacy", "localRoute", "testCases"]
    for id, m in SKILLS.items():
        unknown = set(m) - set(order)
        assert not unknown, (id, unknown)
        doc = {k: m[k] for k in order if k in m}
        with open(os.path.join(outdir, id + ".json"), "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=2)
            f.write("\n")
    print(len(SKILLS), "manifiestos")

if __name__ == "__main__":
    main(sys.argv[1])
