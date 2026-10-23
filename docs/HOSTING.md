# Dónde alojar el servidor de JARVIS

La app release solo habla con servidores **HTTPS**. El servidor es pequeño (Node, sin base de
datos) y consume poca CPU: el gasto real es el uso de OpenAI, no el hosting.

> Los precios de proveedores cambian. Este documento no da cifras de hosting porque no se pudieron
> verificar desde el entorno donde se escribió; revisa la página de cada proveedor.
> Costos de OpenAI: `JARVIS-KB/07-RESEARCH/costos.md`.

## Comparación rápida

| Opción | Costo del hosting | Ventajas | Desventajas |
|---|---|---|---|
| A. Tu PC + Tailscale | Gratis (plan personal) | Privado: el servidor no queda expuesto a internet; HTTPS automático en `*.ts.net` | La PC debe estar encendida; el teléfono necesita la app Tailscale |
| B. Tu PC + Cloudflare Tunnel | Gratis | Sin abrir puertos; dominio propio con HTTPS | Requiere un dominio en Cloudflare; queda accesible desde internet (protegido por el token) |
| C. VPS pequeño + Caddy | Bajo, mensual | Siempre encendido; control total | Tú administras actualizaciones y seguridad |
| D. Plataforma gestionada (Render, Railway, Fly.io…) | Variable; algunos tienen plan gratuito que se "duerme" | Despliegue con `git push`; HTTPS incluido | Los planes que se duermen añaden segundos a la primera petición; los contadores de uso se reinician al reiniciar |

Para empezar: **A** (privada y gratis). Para uso diario sin depender de la PC: **C** o **D**.

**Latencia de voz:** en el modo "Unificado" solo la negociación inicial pasa por tu servidor; el
audio va directo entre el teléfono y OpenAI. Aun así, conviene un servidor en una región cercana.

## A. PC de casa con Tailscale

1. Instala Tailscale en la PC y en el teléfono, con la misma cuenta.
2. En la PC: `cd backend && npm start`.
3. Publica el puerto dentro de tu red privada con HTTPS: `tailscale serve --bg 8787`
   (consulta `tailscale serve --help` en tu versión).
4. En la app: URL `https://NOMBRE-DE-TU-PC.TU-RED.ts.net`.

## B. PC de casa con Cloudflare Tunnel

1. Instala `cloudflared` y autentícate con tu cuenta de Cloudflare.
2. Crea un túnel que apunte a `http://localhost:8787` con un subdominio, p. ej.
   `jarvis.tudominio.com` (sigue la guía oficial de Cloudflare Tunnel).
3. En `backend/.env`: `TRUST_PROXY=true`.

## C. VPS con Caddy (HTTPS automático)

```bash
# En el servidor (Debian/Ubuntu), con Node 22 instalado
git clone <tu-repositorio> jarvis && cd jarvis/backend
npm ci --omit=dev
cp .env.example .env && nano .env      # pon tus claves AQUÍ, en el servidor
```

`/etc/systemd/system/jarvis.service`:
```ini
[Unit]
Description=JARVIS backend
After=network-online.target

[Service]
WorkingDirectory=/home/jarvis/jarvis/backend
ExecStart=/usr/bin/node --env-file=.env src/server.js
Restart=on-failure
User=jarvis
Environment=HOST=127.0.0.1
Environment=TRUST_PROXY=true

[Install]
WantedBy=multi-user.target
```

`/etc/caddy/Caddyfile` (Caddy obtiene el certificado solo):
```
jarvis.tudominio.com {
    reverse_proxy 127.0.0.1:8787
}
```

```bash
sudo systemctl enable --now jarvis caddy
curl https://jarvis.tudominio.com/health
```

## D. Plataforma gestionada

### Render (archivo `render.yaml` incluido)
1. Sube el repositorio a GitHub y, en Render, **New → Blueprint** → elige el repositorio y la rama.
2. Render lee `render.yaml` y pide los valores marcados `sync: false`: `OPENAI_API_KEY`,
   `UN_COMTRADE_API_KEY`, `GOOGLE_MAPS_API_KEY`, `OPUSCLIP_API_KEY`, `TAVILY_API_KEY` (opcional) y
   `OSM_CONTACT` (tu correo).
   Si ya tienes un servicio con esas claves, cópialas desde su pestaña **Environment**.
3. `JARVIS_CLIENT_TOKEN` lo genera Render: cópialo de **Environment** a la app (Ajustes → Servidor),
   junto con la URL `https://<servicio>.onrender.com`.
4. Comprueba `https://<servicio>.onrender.com/health`: dice qué funciones están activas, nunca las claves.
5. **JARVIS Web:** abre `https://<servicio>.onrender.com/` en el navegador (computadora o teléfono),
   pega el `JARVIS_CLIENT_TOKEN` cuando lo pida y listo. No hace falta instalar nada.

El plan gratuito se duerme tras un rato sin uso; la primera petición puede tardar. Para la voz es mejor
un plan de pago. Los contadores de uso se reinician con cada reinicio.

### Otras plataformas
- Directorio raíz del servicio: `backend`. Comando de inicio: `node src/server.js`.
- Variables: cópialas de `.env.example` en el **panel de secretos** del proveedor (nunca en el
  repositorio). Añade `TRUST_PROXY=true`.
- Comprobación de salud: `/health`.

## Seguridad en cualquier opción

- `JARVIS_CLIENT_TOKEN` largo y aleatorio (`npm run gen-token`). Si se filtra, cámbialo en el
  servidor y en la app.
- Pon un límite de gasto en tu cuenta de OpenAI además de los límites del servidor
  (`REALTIME_MAX_SESSIONS_PER_DAY`, `CHAT_MAX_REQUESTS_PER_DAY`).
- Revisa `/api/usage` (o Ajustes → Probar conexión) para ver el consumo del día.
- Los contadores de uso viven en memoria: se reinician cuando el servidor se reinicia.
