# JARVIS — GUÍA PASO A PASO PARA PUBLICAR EN GITHUB DESKTOP Y DESPLEGAR EN RENDER

Sigue esta guía sencilla para publicar de forma segura el proyecto JARVIS en GitHub Desktop y desplegar la pasarela backend en Render.com.

---

## PASO 1: ABRIR EL PROYECTO EN GITHUB DESKTOP
1. Abre la aplicación **GitHub Desktop** en tu computadora.
2. Ve al menú superior **File** -> **Add Local Repository...** (o presiona `Ctrl + O`).
3. Haz clic en **Choose...** y selecciona la carpeta raíz del proyecto:
   `C:\Users\PC\AndroidStudioProjects\JARVIS`
4. Haz clic en **Add Repository**.

---

## PASO 2: REALIZAR EL PRIMER COMMIT LOCAL
1. En la esquina inferior izquierda de GitHub Desktop, en la casilla **Summary (required)**, escribe:
   `Inicializar repositorio JARVIS con Backend y Render`
2. Haz clic en el botón azul **Commit to master** (o `main`).

---

## PASO 3: PUBLICAR EN GITHUB DE FORMA PRIVADA (MUY IMPORTANTE)
1. En la parte superior de GitHub Desktop, haz clic en el botón azul **Publish repository**.
2. **VERIFICA QUE LA CASILLA "Keep this code private" ESTÉ MARCADA** (para proteger tu código).
3. Haz clic en **Publish Repository**.
4. GitHub Desktop creará el repositorio privado en tu cuenta de GitHub de forma automática.

---

## PASO 4: CREAR EL SERVICIO EN RENDER.COM
1. Abre tu navegador e ingresa a [dashboard.render.com](https://dashboard.render.com/).
2. Inicia sesión en Render (puedes iniciar sesión con tu cuenta de GitHub).
3. En la esquina superior derecha, haz clic en **New +** -> **Blueprint**.
4. Conecta tu cuenta de GitHub y selecciona el repositorio privado **JARVIS**.
5. Render detectará automáticamente el archivo `render.yaml` del proyecto.

---

## PASO 5: CONFIGURAR LAS CLAVES SECRETAS EN RENDER
En el formulario de configuración de Render, llena las variables de entorno con tus claves secretas:
* `OPENAI_API_KEY`: Tu clave secreta de OpenAI (`sk-proj-...`).
* `GEMINI_API_KEY`: Tu clave secreta de Google Gemini (`AIzaSy...`).

Haz clic en **Apply**. Render compilará e iniciará el servicio automáticamente.

---

## PASO 6: VERIFICAR Y CONECTAR JARVIS EN EL TELÉFONO
1. Una vez desplegado, copia la URL pública que te asigna Render (ejemplo: `https://jarvis-backend-gateway.onrender.com`).
2. Abre la URL en el navegador de tu teléfono agregando `/health` al final:
   `https://jarvis-backend-gateway.onrender.com/health`
   Debe responder: `{"status":"ok",...}`.
3. Abre JARVIS en tu teléfono POCO X6 Pro, ve a **Ajustes -> Servidor Backend**, pega tu URL pública y toca **Guardar URL**.
