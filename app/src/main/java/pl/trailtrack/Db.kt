package pl.trailtrack

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey val id: Long,
    val finished: Boolean = false,
    val name: String = "",
    val distanceM: Double = 0.0,
    val movingSec: Double = 0.0,
    val elapsedSec: Double = 0.0,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val maxSpeedMs: Double = 0.0,
    val kcal: Double = 0.0,
    val pointCount: Int = 0,
    val terrainEnc: String = ""
)

@Entity(
    tableName = "points",
    foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["rideId", "idx"])]
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val pid: Long = 0,
    val rideId: Long,
    val idx: Int,
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val time: Long,
    val speed: Double,
    val terrain: String,
    val brk: Boolean
)

@Entity(
    tableName = "laps",
    foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["rideId"])]
)
data class LapEntity(
    @PrimaryKey(autoGenerate = true) val lid: Long = 0,
    val rideId: Long,
    val pointIdx: Int
)

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val distanceM: Double,
    val ascentM: Double,
    val pointCount: Int
)

@Entity(
    tableName = "route_points",
    foreignKeys = [ForeignKey(entity = RouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["routeId", "idx"])]
)
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true) val pid: Long = 0,
    val routeId: Long,
    val idx: Int,
    val lat: Double,
    val lon: Double,
    val ele: Double
)

@Dao
interface RideDao {
    @Insert suspend fun insertRide(r: RideEntity)
    @Update suspend fun updateRide(r: RideEntity)
    @Insert suspend fun insertPoints(p: List<PointEntity>)
    @Insert suspend fun insertLaps(l: List<LapEntity>)

    @Query("SELECT * FROM rides WHERE finished = 1 ORDER BY id DESC")
    fun observeRides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun getRide(id: Long): RideEntity?

    @Query("SELECT * FROM points WHERE rideId = :id ORDER BY idx")
    suspend fun getPoints(id: Long): List<PointEntity>

    @Query("SELECT * FROM laps WHERE rideId = :id ORDER BY pointIdx")
    suspend fun getLaps(id: Long): List<LapEntity>

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun deleteRide(id: Long)

    @Query("SELECT * FROM rides WHERE finished = 0")
    suspend fun getUnfinished(): List<RideEntity>

    @Query("UPDATE rides SET name = :name WHERE id = :id")
    suspend fun renameRide(id: Long, name: String)

    // trasy do podążania
    @Insert suspend fun insertRoute(r: RouteEntity): Long
    @Insert suspend fun insertRoutePoints(p: List<RoutePointEntity>)

    @Query("SELECT * FROM routes ORDER BY createdAt DESC")
    fun observeRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun getRoute(id: Long): RouteEntity?

    @Query("SELECT * FROM route_points WHERE routeId = :id ORDER BY idx")
    suspend fun getRoutePoints(id: Long): List<RoutePointEntity>

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun deleteRoute(id: Long)
}

@Database(
    entities = [RideEntity::class, PointEntity::class, LapEntity::class, RouteEntity::class, RoutePointEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): RideDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "trailtrack.db")
                .build().also { inst = it }
        }
    }
}

fun PointEntity.toTrackPoint() = TrackPoint(
    lat, lon, ele, time, speed,
    runCatching { Terrain.valueOf(terrain) }.getOrDefault(Terrain.ASPHALT), brk
)

fun TrackPoint.toEntity(rideId: Long, idx: Int) = PointEntity(
    rideId = rideId, idx = idx, lat = lat, lon = lon, ele = ele,
    time = time, speed = speed, terrain = terrain.name, brk = brk
)
