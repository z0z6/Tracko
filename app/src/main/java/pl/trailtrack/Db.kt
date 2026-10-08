package pl.trailtrack

import android.content.Context
import androidx.room.ColumnInfo
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val terrainEnc: String = "",
    // analityka (v2)
    @ColumnInfo(defaultValue = "0") val avgHr: Int = 0,
    @ColumnInfo(defaultValue = "0") val maxHr: Int = 0,
    @ColumnInfo(defaultValue = "0") val avgPower: Int = 0,
    @ColumnInfo(defaultValue = "0") val normPower: Int = 0,
    @ColumnInfo(defaultValue = "0.0") val tss: Double = 0.0,
    /** 0 = brak, 1 = z mocy, 2 = z tętna (hrTSS) */
    @ColumnInfo(defaultValue = "0") val tssSource: Int = 0,
    /** najszybszy kilometr w sekundach; 0 = brak (przejazd < 1 km), -1 = jeszcze nie policzony (v3) */
    @ColumnInfo(defaultValue = "-1.0") val bestKmSec: Double = -1.0,
    /** [Sport.id]: 0 = rower (domyślnie, także dla przejazdów sprzed v4) */
    @ColumnInfo(defaultValue = "0") val sport: Int = 0,
    /** 0 = nawierzchnie jeszcze nie wykryte, 1 = wykryte z mapy (OSM), 2 = ustawione ręcznie / zapisane w starszej wersji */
    @ColumnInfo(defaultValue = "0") val terrainAuto: Int = 0
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
    val brk: Boolean,
    @ColumnInfo(defaultValue = "0") val hr: Int = 0,
    @ColumnInfo(defaultValue = "0") val power: Int = 0,
    @ColumnInfo(defaultValue = "0") val cad: Int = 0
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

/** Odcinek do ścigania się z duchem. [geom] = "lat,lon;lat,lon;…" (6 miejsc po przecinku). */
@Entity(tableName = "segments")
data class SegmentEntity(
    @PrimaryKey val uid: String,
    val name: String,
    val sport: Int,
    val lengthM: Double,
    val geom: String,
    val createdAt: Long,
    val author: String
)

/** Wynik na odcinku. [profile] = 101 czasów (s) w punktach co 1% długości odcinka – to jest „duch”. */
@Entity(tableName = "segment_efforts", indices = [Index(value = ["segmentUid"])])
data class EffortEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentUid: String,
    val athlete: String,
    val mine: Int,
    val startedAt: Long,
    val timeSec: Double,
    val profile: String,
    val rideId: Long
)

data class SegBest(val segmentUid: String, val best: Double, val cnt: Int)

@Dao
interface RideDao {
    @Insert suspend fun insertRide(r: RideEntity)
    @Update suspend fun updateRide(r: RideEntity)
    @Insert suspend fun insertPoints(p: List<PointEntity>)
    @Insert suspend fun insertLaps(l: List<LapEntity>)

    @Query("SELECT * FROM rides WHERE finished = 1 ORDER BY id DESC")
    fun observeRides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE finished = 1 ORDER BY id")
    suspend fun getFinished(): List<RideEntity>

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

    // historia do komunikatów głosowych (rekordy, „tempo jak zazwyczaj”)
    @Query("SELECT * FROM rides WHERE finished = 1 AND sport = :sport AND distanceM > 1000 AND movingSec > 180 ORDER BY id DESC LIMIT 10")
    suspend fun recentForBaseline(sport: Int): List<RideEntity>

    @Query("SELECT MIN(bestKmSec) FROM rides WHERE finished = 1 AND sport = :sport AND bestKmSec > 0")
    suspend fun bestKmEver(sport: Int): Double?

    @Query("SELECT MAX(distanceM) FROM rides WHERE finished = 1 AND sport = :sport")
    suspend fun maxDistance(sport: Int): Double?

    @Query("SELECT id FROM rides WHERE finished = 1 AND bestKmSec < 0")
    suspend fun idsNeedingBestKm(): List<Long>

    @Query("UPDATE rides SET bestKmSec = :v WHERE id = :id")
    suspend fun setBestKm(id: Long, v: Double)

    @Query("UPDATE points SET terrain = :terrain WHERE rideId = :rideId AND idx BETWEEN :from AND :to")
    suspend fun setTerrainRange(rideId: Long, from: Int, to: Int, terrain: String)

    /** Wersja blokująca – do użycia wewnątrz transakcji na wątku roboczym. */
    @Query("UPDATE points SET terrain = :terrain WHERE rideId = :rideId AND idx BETWEEN :from AND :to")
    fun setTerrainRangeSync(rideId: Long, from: Int, to: Int, terrain: String)

    @Query("UPDATE rides SET terrainAuto = :v WHERE id = :id")
    suspend fun setTerrainAuto(id: Long, v: Int)

    // ----- odcinki i duchy -----
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun upsertSegment(s: SegmentEntity)
    @Query("SELECT * FROM segments ORDER BY createdAt DESC") fun observeSegments(): Flow<List<SegmentEntity>>
    @Query("SELECT * FROM segments WHERE sport = :sport") suspend fun segmentsForSport(sport: Int): List<SegmentEntity>
    @Query("SELECT * FROM segments WHERE uid = :uid") suspend fun getSegment(uid: String): SegmentEntity?
    @Query("DELETE FROM segments WHERE uid = :uid") suspend fun deleteSegment(uid: String)

    @Insert suspend fun insertEffort(e: EffortEntity): Long
    @Query("SELECT * FROM segment_efforts WHERE segmentUid = :uid ORDER BY timeSec ASC") fun observeEfforts(uid: String): Flow<List<EffortEntity>>
    @Query("SELECT * FROM segment_efforts WHERE segmentUid = :uid ORDER BY timeSec ASC") suspend fun efforts(uid: String): List<EffortEntity>
    @Query("SELECT * FROM segment_efforts WHERE id = :id") suspend fun getEffort(id: Long): EffortEntity?
    @Query("DELETE FROM segment_efforts WHERE segmentUid = :uid") suspend fun deleteEfforts(uid: String)
    @Query("DELETE FROM segment_efforts WHERE id = :id") suspend fun deleteEffort(id: Long)
    @Query("SELECT COUNT(*) FROM segment_efforts WHERE segmentUid = :uid AND athlete = :athlete AND startedAt = :startedAt")
    suspend fun effortExists(uid: String, athlete: String, startedAt: Long): Int
    @Query("SELECT segmentUid, MIN(timeSec) AS best, COUNT(*) AS cnt FROM segment_efforts GROUP BY segmentUid")
    fun observeSegBest(): Flow<List<SegBest>>
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE points ADD COLUMN hr INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE points ADD COLUMN power INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE points ADD COLUMN cad INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rides ADD COLUMN avgHr INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rides ADD COLUMN maxHr INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rides ADD COLUMN avgPower INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rides ADD COLUMN normPower INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rides ADD COLUMN tss REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE rides ADD COLUMN tssSource INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rides ADD COLUMN bestKmSec REAL NOT NULL DEFAULT -1.0")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rides ADD COLUMN sport INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `segments` (`uid` TEXT NOT NULL, `name` TEXT NOT NULL, `sport` INTEGER NOT NULL, " +
                "`lengthM` REAL NOT NULL, `geom` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `author` TEXT NOT NULL, PRIMARY KEY(`uid`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `segment_efforts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `segmentUid` TEXT NOT NULL, " +
                "`athlete` TEXT NOT NULL, `mine` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `timeSec` REAL NOT NULL, " +
                "`profile` TEXT NOT NULL, `rideId` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_segment_efforts_segmentUid` ON `segment_efforts` (`segmentUid`)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rides ADD COLUMN terrainAuto INTEGER NOT NULL DEFAULT 0")
        // dotychczasowe aktywności mają nawierzchnie zapisane w trakcie jazdy – nie nadpisujemy ich automatem
        db.execSQL("UPDATE rides SET terrainAuto = 2 WHERE finished = 1")
    }
}

@Database(
    entities = [
        RideEntity::class, PointEntity::class, LapEntity::class, RouteEntity::class, RoutePointEntity::class,
        SegmentEntity::class, EffortEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): RideDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "trailtrack.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build().also { inst = it }
        }
    }
}

fun PointEntity.toTrackPoint() = TrackPoint(
    lat, lon, ele, time, speed,
    runCatching { Terrain.valueOf(terrain) }.getOrDefault(Terrain.ASPHALT), brk, hr, power, cad
)

fun TrackPoint.toEntity(rideId: Long, idx: Int) = PointEntity(
    rideId = rideId, idx = idx, lat = lat, lon = lon, ele = ele,
    time = time, speed = speed, terrain = terrain.name, brk = brk, hr = hr, power = power, cad = cad
)
