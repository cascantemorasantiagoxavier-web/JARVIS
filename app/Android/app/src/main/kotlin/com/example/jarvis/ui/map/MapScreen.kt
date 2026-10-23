package com.example.jarvis.ui.map

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.jarvis.AppContainer
import com.example.jarvis.BuildConfig
import com.example.jarvis.core.business.Business
import com.example.jarvis.core.business.FieldStatus
import com.example.jarvis.data.repo.toModel
import com.example.jarvis.ui.cards.BusinessAction
import com.example.jarvis.ui.cards.CardActions
import com.example.jarvis.ui.components.HudChip
import com.example.jarvis.ui.components.HudPanel
import com.example.jarvis.ui.components.ScreenScaffold
import com.example.jarvis.ui.theme.HudText
import com.example.jarvis.ui.theme.LocalHud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Mapa bajo petición (OpenStreetMap vía osmdroid, sin clave). Solo se abre cuando el usuario lo
 * pide. Muestra únicamente negocios con coordenadas aportadas por la fuente: sin marcadores inventados.
 */
@Composable
fun MapScreen(c: AppContainer, ids: List<String>, showProspects: Boolean, actions: CardActions, onBack: () -> Unit) {
    val hud = LocalHud.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var businesses by remember { mutableStateOf<List<Business>>(emptyList()) }
    var selected by remember { mutableStateOf<Business?>(null) }

    LaunchedEffect(ids, showProspects) {
        businesses = if (showProspects) {
            withContext(Dispatchers.IO) { c.db.prospects().all().map { it.toModel() } }.mapNotNull { it.snapshot() }
        } else {
            ids.mapNotNull { id -> c.working.business(id) ?: runCatching { c.backend.business(id) }.getOrNull() }
        }
    }
    val withCoords = businesses.filter { it.mapPoint != null }
    val missing = businesses.size - withCoords.size

    // Política de uso de teselas de OSM: User-Agent identificable
    remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().setUserAgentValue("JARVIS-Assistant/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
        // Caché de teselas en el almacenamiento privado de la app (sin permisos de almacenamiento).
        val base = java.io.File(context.cacheDir, "osmdroid")
        Configuration.getInstance().setOsmdroidBasePath(base)
        Configuration.getInstance().setOsmdroidTileCache(java.io.File(base, "tiles"))
        true
    }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            overlays.add(CopyrightOverlay(context).apply { setTextColor(android.graphics.Color.LTGRAY) })
            controller.setZoom(8.0)
            controller.setCenter(GeoPoint(9.93, -84.08))
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); mapView.onDetach() }
    }
    LaunchedEffect(withCoords) {
        mapView.overlays.removeAll { it is Marker }
        val accent = hud.primary.toArgb()
        withCoords.forEach { b ->
            val p = b.mapPoint!!
            val marker = Marker(mapView).apply {
                position = GeoPoint(p.lat, p.lon)
                title = b.displayName
                snippet = if (b.coordinates.status == FieldStatus.APPROXIMATE) "Ubicación aproximada" else b.category.value
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                    setStroke(4, android.graphics.Color.WHITE)
                    setSize(36, 36)
                }
                setOnMarkerClickListener { _, _ -> selected = b; true }
            }
            mapView.overlays.add(marker)
        }
        if (withCoords.size == 1) {
            val p = withCoords.first().mapPoint!!
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(GeoPoint(p.lat, p.lon))
        } else if (withCoords.size > 1) {
            val box = BoundingBox.fromGeoPoints(withCoords.map { GeoPoint(it.mapPoint!!.lat, it.mapPoint!!.lon) })
            mapView.post { mapView.zoomToBoundingBox(box.increaseByScale(1.25f), false, 48) }
        }
        mapView.invalidate()
    }

    ScreenScaffold("Mapa", onBack) {
        Text(
            when {
                businesses.isEmpty() -> "No hay negocios para mostrar. Pídeme una búsqueda primero."
                missing > 0 -> "${withCoords.size} con coordenadas en el mapa · $missing sin coordenadas en la fuente (no se muestran)."
                else -> "${withCoords.size} negocios con coordenadas de la fuente."
            },
            style = HudText.bodySmall, color = hud.textDim, modifier = Modifier.padding(vertical = 6.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f).testTag("map")) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            selected?.let { b ->
                HudPanel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), title = b.displayName, cut = 10.dp) {
                    Text(b.category.value ?: "Categoría no disponible", style = HudText.bodySmall, color = hud.textDim)
                    Text("Dirección: ${b.address.value ?: "No disponible"}", style = HudText.bodySmall, color = hud.text)
                    Text("Teléfono: ${b.phone.value ?: "No disponible"}", style = HudText.bodySmall, color = hud.text)
                    if (b.coordinates.status == FieldStatus.APPROXIMATE) Text("Ubicación aproximada (centro del edificio o parcela)", style = HudText.bodySmall, color = hud.warn)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        HudChip("Abrir en app de mapas", { actions.business(BusinessAction.LOCATION, b) }, icon = Icons.Outlined.OpenInNew)
                        Spacer(Modifier.padding(4.dp))
                        HudChip("Ruta", { actions.business(BusinessAction.DIRECTIONS, b) }, icon = Icons.Outlined.Directions)
                        Spacer(Modifier.padding(4.dp))
                        HudChip("Cerrar", { selected = null })
                    }
                }
            }
        }
        Column(Modifier.padding(vertical = 4.dp)) {
            Text("Mapa © colaboradores de OpenStreetMap (ODbL). Las rutas las calcula tu app de mapas.", style = HudText.label, color = hud.textFaint)
        }
    }
}
