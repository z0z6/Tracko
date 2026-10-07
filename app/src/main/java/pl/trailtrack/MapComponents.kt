package pl.trailtrack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Własny serwer kafelków: URL z {z}/{x}/{y}. Pozwala na pobieranie map offline (CacheManager). */
class TemplateTileSource(name: String, private val template: String) :
    OnlineTileSourceBase(name, 0, 19, 256, ".png", arrayOf(template)) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        template
            .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
            .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
            .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
}

fun tileSourceFor(mode: Int): ITileSource {
    val url = Prefs.customTileUrl.trim()
    return when {
        mode == 2 && url.isNotBlank() -> TemplateTileSource("Custom-" + url.hashCode(), url)
        mode == 1 -> TileSourceFactory.OpenTopo
        else -> TileSourceFactory.MAPNIK
    }
}

@Composable
fun rememberMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(52.0, 19.4))
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause()
            mapView.onDetach()
        }
    }
    return mapView
}

private fun splitRuns(points: List<TrackPoint>): List<Pair<Terrain, List<GeoPoint>>> {
    val out = ArrayList<Pair<Terrain, List<GeoPoint>>>()
    var cur = ArrayList<GeoPoint>()
    var curT: Terrain? = null
    for (p in points) {
        val g = GeoPoint(p.lat, p.lon)
        if (p.brk && cur.size > 1 && curT != null) {
            out.add(curT to cur)
            cur = ArrayList()
            curT = null
        } else if (curT != null && p.terrain != curT) {
            out.add(curT to cur)
            cur = arrayListOf(cur.last())
        }
        curT = p.terrain
        cur.add(g)
    }
    if (curT != null && cur.size > 1) out.add(curT to cur)
    return out
}

/**
 * Mapa OSM z trasą kolorowaną wg nawierzchni, opcjonalną trasą do podążania (niebieska),
 * śledzeniem pozycji (follow) i jednorazowym dopasowaniem widoku (fit).
 */
@Composable
fun TrackMap(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    route: List<GeoPoint> = emptyList(),
    follow: Boolean = false,
    fit: Boolean = false
) {
    var mode by remember { mutableIntStateOf(Prefs.mapMode) }
    val zoomedOnce = remember { booleanArrayOf(false) }
    val fitted = remember { booleanArrayOf(false) }
    val mapView = rememberMapView()

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                val src = tileSourceFor(mode)
                if (mv.tileProvider.tileSource.name() != src.name()) mv.setTileSource(src)

                mv.overlays.clear()
                if (route.size >= 2) {
                    mv.overlays.add(Polyline().apply {
                        outlinePaint.color = 0xAA007AFF.toInt()
                        outlinePaint.strokeWidth = 14f
                        setPoints(route)
                    })
                }
                for ((t, geo) in splitRuns(points)) {
                    mv.overlays.add(Polyline().apply {
                        outlinePaint.color = t.color
                        outlinePaint.strokeWidth = 9f
                        setPoints(geo)
                    })
                }
                val last = points.lastOrNull()
                if (follow && last != null) {
                    mv.overlays.add(Marker(mv).apply {
                        position = GeoPoint(last.lat, last.lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                    if (!zoomedOnce[0]) {
                        mv.controller.setZoom(16.0)
                        zoomedOnce[0] = true
                    }
                    mv.controller.setCenter(GeoPoint(last.lat, last.lon))
                } else if (fit && !fitted[0]) {
                    val geo = if (points.size >= 2) points.map { GeoPoint(it.lat, it.lon) } else route
                    if (geo.size >= 2) {
                        val box = BoundingBox.fromGeoPoints(geo)
                        fitted[0] = true
                        mv.post { mv.zoomToBoundingBox(box, false, 80) }
                    }
                }
                mv.invalidate()
            }
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(10.dp).size(38.dp).clip(CircleShape)
                .background(Color(0xCCFFFFFF))
                .clickable {
                    val hasCustom = Prefs.customTileUrl.isNotBlank()
                    mode = when (mode) {
                        0 -> 1
                        1 -> if (hasCustom) 2 else 0
                        else -> 0
                    }
                    Prefs.mapMode = mode
                },
            contentAlignment = Alignment.Center
        ) { Text("🗺", fontSize = 18.sp) }
        Text(
            when (mode) {
                1 -> "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)"
                2 -> "Mapa: własny serwer kafelków"
                else -> "© OpenStreetMap contributors"
            },
            modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAAFFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
            fontSize = 10.sp, color = Color.Black
        )
    }
}
