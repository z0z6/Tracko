package pl.trailtrack

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Rodzaje nawierzchni, które rowerzysta może przełączać w trakcie jazdy. */
enum class Terrain(val label: String, val color: Int) {
    ASPHALT("Asfalt", 0xFF607D8B.toInt()),
    COBBLES("Kostka/bruk", 0xFF9C27B0.toInt()),
    GRAVEL("Szuter", 0xFFFFA000.toInt()),
    DIRT("Droga leśna/polna", 0xFF8D6E63.toInt()),
    SINGLETRACK("Singletrack", 0xFF2E7D32.toInt()),
    MUD("Błoto", 0xFF3E2723.toInt()),
    SAND("Piasek", 0xFFFDD835.toInt()),
    SNOW("Śnieg/lód", 0xFF03A9F4.toInt())
}

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val time: Long,
    val speed: Double,
    val terrain: Terrain
)

/** id = czas startu w ms. */
data class Ride(val id: Long, val points: List<TrackPoint>)

data class LiveState(
    val recording: Boolean = false,
    val startTime: Long = 0L,
    val points: List<TrackPoint> = emptyList(),
    val terrain: Terrain = Terrain.ASPHALT
)

/** Stan nagrywania współdzielony między serwisem a UI (ten sam proces). */
object Live {
    val state = MutableStateFlow(LiveState())
    fun setTerrain(t: Terrain) = state.update { it.copy(terrain = t) }
}
