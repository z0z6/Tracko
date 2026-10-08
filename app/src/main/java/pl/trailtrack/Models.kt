package pl.trailtrack

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Nawierzchnie. effort = mnożnik wysiłku używany do szacowania kalorii. */
enum class Terrain(val label: String, val color: Int, val effort: Double) {
    ASPHALT("Asfalt", 0xFF607D8B.toInt(), 1.0),
    COBBLES("Kostka/bruk", 0xFF9C27B0.toInt(), 1.05),
    GRAVEL("Szuter", 0xFFFFA000.toInt(), 1.1),
    DIRT("Droga leśna/polna", 0xFF8D6E63.toInt(), 1.1),
    SINGLETRACK("Singletrack", 0xFF2E7D32.toInt(), 1.2),
    MUD("Błoto", 0xFF3E2723.toInt(), 1.3),
    SAND("Piasek", 0xFFFDD835.toInt(), 1.3),
    SNOW("Śnieg/lód", 0xFF03A9F4.toInt(), 1.3)
}

/**
 * Rodzaje aktywności. Dla aktywności bez GPS ([gps] = false) punkty mają lat/lon = 0, a dystans liczymy
 * jako prędkość × czas (bieżnia: ustawiona prędkość; basen: długość basenu / czas długości).
 */
enum class Sport(
    val id: Int,
    val label: String,
    /** do automatycznych nazw: „Poranny ${noun}” */
    val noun: String,
    val gps: Boolean,
    val hasDistance: Boolean,
    /** 0 = km/h, 1 = min/km, 2 = min/100 m */
    val pace: Int,
    val minMoveSpeed: Double,
    val moveGapSec: Double,
    val splitM: Double
) {
    CYCLING(0, "Rower", "przejazd", true, true, 0, 0.8, 60.0, 1000.0),
    RUNNING(1, "Bieganie", "bieg", true, true, 1, 0.8, 60.0, 1000.0),
    SWIMMING(2, "Pływanie", "trening pływacki", false, true, 2, 0.2, 400.0, 100.0),
    STRENGTH(3, "Siłownia", "trening siłowy", false, false, 0, 0.0, 0.0, 1000.0),
    TREADMILL(4, "Bieżnia", "bieg na bieżni", false, true, 1, 0.3, 60.0, 1000.0),
    SKIING(5, "Narty biegowe", "przejazd na nartach", true, true, 0, 0.8, 60.0, 1000.0);

    /** bez dystansu (siłownia) cały czas aktywny liczymy jako „w ruchu” */
    val timeIsMoving: Boolean get() = !hasDistance

    companion object {
        fun fromId(id: Int): Sport = values().firstOrNull { it.id == id } ?: CYCLING
    }
}

/** Stan ścigania z duchem na żywo (aktualizowany przez GhostEngine w serwisie). */
data class GhostLive(
    val segmentUid: String,
    val segmentName: String,
    val ghostName: String,
    /** 0 = dojedź do startu, 1 = trwa ściganie, 2 = ukończono, 3 = przerwano (zjazd z odcinka) */
    val status: Int,
    val distToStartM: Double = 0.0,
    val progressM: Double = 0.0,
    val lengthM: Double = 0.0,
    val elapsedSec: Double = 0.0,
    /** > 0 = za duchem, < 0 = przed duchem */
    val gapSec: Double = 0.0,
    val ghostLat: Double = 0.0,
    val ghostLon: Double = 0.0,
    val resultSec: Double = 0.0,
    val ghostTotalSec: Double = 0.0
)

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val time: Long,
    val speed: Double,
    val terrain: Terrain,
    /** true = pierwszy punkt po pauzie: nie liczymy odcinka od poprzedniego punktu */
    val brk: Boolean = false,
    val hr: Int = 0,
    val power: Int = 0,
    val cad: Int = 0
)

enum class PauseKind { NONE, MANUAL, AUTO }

data class LiveState(
    val recording: Boolean = false,
    val startTime: Long = 0L,
    val points: List<TrackPoint> = emptyList(),
    val terrain: Terrain = Terrain.ASPHALT,
    val pause: PauseKind = PauseKind.NONE,
    /** indeksy punktów, w których zaczyna się nowe okrążenie */
    val laps: List<Int> = emptyList(),
    val needBreak: Boolean = false,
    val sport: Sport = Sport.CYCLING,
    /** bieżnia: ustawiona prędkość (m/s) i nachylenie (%) */
    val treadSpeedMs: Double = 0.0,
    val inclinePct: Double = 0.0,
    /** pływanie: liczba przepłyniętych długości */
    val lengths: Int = 0,
    /** aktywności bez GPS: czas aktywny (bez pauz) w ms, aktualizowany co sekundę */
    val activeMs: Long = 0L,
    val ghost: GhostLive? = null
)

/** Stan nagrywania współdzielony między serwisem a UI (ten sam proces). */
object Live {
    val state = MutableStateFlow(LiveState())

    fun setTerrain(t: Terrain) = state.update { it.copy(terrain = t) }

    /** Wybór aktywności (tylko poza nagrywaniem). Narty ustawiają śnieg, powrót z nart – asfalt. */
    fun setSport(sp: Sport) = state.update {
        if (it.recording) it else it.copy(
            sport = sp,
            terrain = when {
                sp == Sport.SKIING -> Terrain.SNOW
                it.sport == Sport.SKIING -> Terrain.ASPHALT
                else -> it.terrain
            }
        )
    }

    fun setTreadSpeed(ms: Double) = state.update { it.copy(treadSpeedMs = ms.coerceIn(0.0, 8.5)) }

    fun setIncline(pct: Double) = state.update { it.copy(inclinePct = pct.coerceIn(-3.0, 15.0)) }

    /**
     * Pływanie: dotknięcie „+ Długość”. Punkt zapisuje prędkość = długość basenu / czas od poprzedniego
     * punktu, więc dystans (prędkość × czas) wychodzi dokładnie tyle, ile przepłynięto.
     */
    fun addLength(poolM: Int) {
        val hr = SensorHub.snapshot().hr
        state.update { s ->
            val last = s.points.lastOrNull()
            if (!s.recording || s.sport != Sport.SWIMMING || s.pause != PauseKind.NONE || last == null) s
            else {
                val now = System.currentTimeMillis()
                val dt = (now - last.time) / 1000.0
                if (dt < 3.0) s   // ochrona przed podwójnym dotknięciem
                else s.copy(
                    points = s.points + TrackPoint(0.0, 0.0, 0.0, now, poolM / dt, Terrain.ASPHALT, hr = hr),
                    lengths = s.lengths + 1
                )
            }
        }
    }

    fun pause() = state.update {
        if (it.recording && it.pause == PauseKind.NONE) it.copy(pause = PauseKind.MANUAL) else it
    }

    fun resume() = state.update {
        when {
            it.pause == PauseKind.NONE -> it
            // pływanie: punkt „przerwy” w chwili wznowienia, żeby pierwsza długość liczyła się od teraz
            it.sport == Sport.SWIMMING && it.recording -> it.copy(
                pause = PauseKind.NONE, needBreak = false,
                points = it.points + TrackPoint(0.0, 0.0, 0.0, System.currentTimeMillis(), 0.0, Terrain.ASPHALT, brk = true)
            )
            else -> it.copy(pause = PauseKind.NONE, needBreak = true)
        }
    }

    fun lap() = state.update { s ->
        val idx = s.points.size - 1
        if (s.recording && idx >= 1 && (s.laps.lastOrNull() ?: 0) < idx) s.copy(laps = s.laps + idx) else s
    }
}

fun encodeTerrain(m: Map<Terrain, Double>): String =
    m.entries.joinToString(";") { "${it.key.name}=${it.value}" }

fun decodeTerrain(s: String): Map<Terrain, Double> {
    val out = LinkedHashMap<Terrain, Double>()
    for (part in s.split(";")) {
        val kv = part.split("=")
        if (kv.size != 2) continue
        val t = runCatching { Terrain.valueOf(kv[0]) }.getOrNull() ?: continue
        val d = kv[1].toDoubleOrNull() ?: continue
        out[t] = d
    }
    return out
}
