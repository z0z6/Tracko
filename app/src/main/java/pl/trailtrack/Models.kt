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

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val time: Long,
    val speed: Double,
    val terrain: Terrain,
    /** true = pierwszy punkt po pauzie: nie liczymy odcinka od poprzedniego punktu */
    val brk: Boolean = false
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
    val needBreak: Boolean = false
)

/** Stan nagrywania współdzielony między serwisem a UI (ten sam proces). */
object Live {
    val state = MutableStateFlow(LiveState())

    fun setTerrain(t: Terrain) = state.update { it.copy(terrain = t) }

    fun pause() = state.update {
        if (it.recording && it.pause == PauseKind.NONE) it.copy(pause = PauseKind.MANUAL) else it
    }

    fun resume() = state.update {
        if (it.pause != PauseKind.NONE) it.copy(pause = PauseKind.NONE, needBreak = true) else it
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
