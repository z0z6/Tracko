package pl.trailtrack

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/**
 * Tętno z Health Connect – dla zegarków, które nie nadają tętna przez Bluetooth (CMF Watch 3 Pro i inne CMF/Nothing,
 * Samsung Galaxy Watch, Fitbit, Xiaomi, Huawei…). Aplikacja producenta (np. Nothing X) zapisuje pomiary do
 * Health Connect, a Tracko po treningu dopasowuje je do punktów trasy, które nie mają tętna z czujnika BLE.
 */
object HealthHr {
    val PERMISSIONS: Set<String> = setOf(HealthPermission.getReadPermission(HeartRateRecord::class))

    /** 0 = dostępne, 1 = trzeba zainstalować / zaktualizować Health Connect, 2 = urządzenie nie obsługuje */
    fun availability(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < 28) return 2
        return when (runCatching { HealthConnectClient.getSdkStatus(ctx) }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)) {
            HealthConnectClient.SDK_AVAILABLE -> 0
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> 1
            // Android 9–13: aplikację Health Connect można doinstalować ze Sklepu Play
            else -> if (Build.VERSION.SDK_INT < 34) 1 else 2
        }
    }

    private fun client(ctx: Context): HealthConnectClient? =
        if (availability(ctx) == 0) runCatching { HealthConnectClient.getOrCreate(ctx) }.getOrNull() else null

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun granted(ctx: Context): Boolean {
        val c = client(ctx) ?: return false
        return runCatching { c.permissionController.getGrantedPermissions().containsAll(PERMISSIONS) }.getOrDefault(false)
    }

    /** Próbki tętna (czas ms, bpm) z przedziału, posortowane; bez danych zapisanych przez samo Tracko. */
    suspend fun samples(ctx: Context, fromMs: Long, toMs: Long): List<Pair<Long, Int>> {
        val c = client(ctx) ?: return emptyList()
        val out = ArrayList<Pair<Long, Int>>()
        var token: String? = null
        var pages = 0
        do {
            val resp = c.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    TimeRangeFilter.between(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs)),
                    pageToken = token
                )
            )
            for (r in resp.records) {
                if (r.metadata.dataOrigin.packageName == ctx.packageName) continue
                for (s in r.samples) {
                    val t = s.time.toEpochMilli()
                    val bpm = s.beatsPerMinute.toInt()
                    if (t in fromMs..toMs && bpm in 25..250) out.add(t to bpm)
                }
            }
            token = resp.pageToken
            pages++
        } while (token != null && pages < 50)
        out.sortBy { it.first }
        return out
    }
}

/**
 * Dopasowanie próbek tętna do punktów trasy: interpolacja liniowa, gdy sąsiednie próbki są ≤ 30 s od siebie,
 * w przeciwnym razie najbliższa próbka do 15 s. Punkty, które już mają tętno (z czujnika BLE), zostają bez zmian.
 * Zwraca nową tablicę wartości (0 = brak).
 */
fun mergeHr(times: LongArray, current: IntArray, samples: List<Pair<Long, Int>>): IntArray {
    val out = current.copyOf()
    if (samples.isEmpty()) return out
    var j = 0
    for (i in times.indices) {
        if (out[i] > 0) continue
        val t = times[i]
        while (j + 1 < samples.size && samples[j + 1].first <= t) j++
        val a = samples[j]
        val b = if (j + 1 < samples.size) samples[j + 1] else null
        val v = when {
            b != null && a.first <= t && b.first >= t && b.first - a.first <= 30_000L && b.first > a.first -> {
                val f = (t - a.first).toDouble() / (b.first - a.first)
                Math.round(a.second + f * (b.second - a.second)).toInt()
            }
            else -> {
                val da = kotlin.math.abs(t - a.first)
                val db = if (b != null) kotlin.math.abs(b.first - t) else Long.MAX_VALUE
                val near = if (da <= db) a else b!!
                if (kotlin.math.abs(near.first - t) <= 15_000L) near.second else 0
            }
        }
        out[i] = v
    }
    return out
}
