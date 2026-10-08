package pl.trailtrack

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
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
import org.osmdroid.views.overlay.Overlay
import androidx.compose.ui.unit.Dp
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

/**
 * Dzieli trasę na odcinki o jednej nawierzchni. Zgodnie ze statystykami (Stats.kt) odcinek
 * poprzedni→bieżący punkt ma nawierzchnię bieżącego punktu. Po pauzie (brk) linia nie łączy
 * punktów przez „dziurę”.
 */
internal fun splitRuns(points: List<TrackPoint>): List<Pair<Terrain, List<GeoPoint>>> {
    val out = ArrayList<Pair<Terrain, List<GeoPoint>>>()
    var cur = ArrayList<GeoPoint>()
    var curT: Terrain? = null
    for ((i, p) in points.withIndex()) {
        val g = GeoPoint(p.lat, p.lon)
        val t = curT
        if (i == 0 || p.brk || t == null) {
            if (t != null && cur.size > 1) out.add(t to cur)
            cur = arrayListOf(g)
            curT = p.terrain
        } else if (p.terrain != t) {
            if (cur.size > 1) out.add(t to cur)
            cur = arrayListOf(GeoPoint(points[i - 1].lat, points[i - 1].lon), g)
            curT = p.terrain
        } else {
            cur.add(g)
        }
    }
    val t = curT
    if (t != null && cur.size > 1) out.add(t to cur)
    return out
}

/** Legenda kolorów nawierzchni na mapie (tylko nawierzchnie użyte na trasie + aktywna). */
@Composable
private fun TerrainLegend(points: List<TrackPoint>, active: Terrain?, modifier: Modifier = Modifier) {
    val used = remember(points.size) { points.map { it.terrain }.toSet() }
    val shown = Terrain.values().filter { it in used || it == active }
    if (shown.isEmpty()) return
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCCFFFFFF)).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (t in shown) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Color(t.color), CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(
                    t.label, fontSize = 11.sp, color = Color.Black,
                    fontWeight = if (t == active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/** „Duch” na mapie: biała kropka z fioletową obwódką i emotikoną. */
private class GhostOverlay(private val pos: GeoPoint, private val dens: Float) : Overlay() {
    private val pt = Point()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5856D6.toInt()
        style = Paint.Style.STROKE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow) return
        mv.projection.toPixels(pos, pt)
        val r = 14f * dens
        ring.strokeWidth = 3f * dens
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, fill)
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, ring)
        text.textSize = 17f * dens
        c.drawText("👻", pt.x.toFloat(), pt.y + 6f * dens, text)
    }
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
    fit: Boolean = false,
    activeTerrain: Terrain? = null,
    /** odcinek do ścigania (pomarańczowy) i pozycja „ducha” na nim */
    segment: List<GeoPoint> = emptyList(),
    ghostPos: GeoPoint? = null,
    /** miejsce u góry mapy zajęte przez nakładkę (np. wybór aktywności) */
    topInset: Dp = 0.dp,
    /** true: ślad kolorowany wg nawierzchni + legenda (podsumowanie); false: jeden kolor (w trakcie nagrywania) */
    colorByTerrain: Boolean = true
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
                        outlinePaint.strokeWidth = 6f * mv.resources.displayMetrics.density
                        setPoints(route)
                    })
                }
                val dens = mv.resources.displayMetrics.density
                if (segment.size >= 2) {
                    for ((col, w) in listOf(0xDDFFFFFF.toInt() to 9f, 0xFFFF9500.toInt() to 6f)) {
                        mv.overlays.add(Polyline().apply {
                            outlinePaint.color = col
                            outlinePaint.strokeWidth = w * dens
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            setPoints(segment)
                        })
                    }
                }
                val runs = splitRuns(points)
                // najpierw jasne obwódki (czytelność na każdej mapie), potem kolorowe linie na wierzchu
                for ((_, geo) in runs) {
                    mv.overlays.add(Polyline().apply {
                        outlinePaint.color = 0xDDFFFFFF.toInt()
                        outlinePaint.strokeWidth = 7f * dens
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        setPoints(geo)
                    })
                }
                for ((t, geo) in runs) {
                    mv.overlays.add(Polyline().apply {
                        outlinePaint.color = if (colorByTerrain) t.color else 0xFF0A84FF.toInt()
                        outlinePaint.strokeWidth = 4.5f * dens
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        setPoints(geo)
                    })
                }
                if (ghostPos != null) mv.overlays.add(GhostOverlay(ghostPos, dens))
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
            Modifier.align(Alignment.TopEnd).padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 10.dp + topInset)
                .size(38.dp).clip(CircleShape)
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
        ) { AppIconView(AppIcon.Layers, Color(0xFF1C1C1E), Modifier.size(20.dp)) }
        if (colorByTerrain) TerrainLegend(points, activeTerrain, Modifier.align(Alignment.TopStart).padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 10.dp + topInset))
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
