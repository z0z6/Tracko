package pl.trailtrack

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class SensorKind(val icon: String, val label: String) {
    HR("❤️", "Tętno"),
    POWER("⚡", "Moc"),
    CSC("🔄", "Prędkość/kadencja"),
    RSC("👟", "Czujnik biegowy (prędkość/kadencja)"),
    FTMS("🏋️", "Trenażer / bieżnia / wioślarz (FTMS)"),
    SPO2("🩸", "Pulsoksymetr (SpO₂)"),
    TEMP("🌡️", "Temperatura ciała")
}

data class SensorInfo(
    val address: String,
    val name: String,
    val connected: Boolean,
    val connecting: Boolean,
    val battery: Int,
    val kinds: Set<SensorKind>,
    /** producent / model z usługi Device Information (jeśli urządzenie ją udostępnia) */
    val maker: String = "",
    /** rozpoznany profil urządzenia, np. zegarek bez transmisji tętna (patrz [KnownDevices]) */
    val hint: String? = null
)

data class FoundDevice(
    val address: String, val name: String, val rssi: Int, val kinds: Set<SensorKind>, val hint: String? = null
)

data class SensorLive(
    val hr: Int = 0, val hrTime: Long = 0L,
    val power: Int = 0, val powerTime: Long = 0L,
    val cadence: Int = 0, val cadTime: Long = 0L,
    val speedMs: Double = 0.0, val speedTime: Long = 0L,
    /** bieg: prędkość i kadencja (kroki/min) z czujnika RSC (foot pod, Stryd, Polar Stride…) */
    val runSpeedMs: Double = 0.0, val runCadence: Int = 0, val runTime: Long = 0L,
    /** urządzenia FTMS: prędkość taśmy/koła, nachylenie (%), kadencja, moc */
    val machineSpeedMs: Double = 0.0, val machineIncline: Double = Double.NaN, val machineTime: Long = 0L,
    val spo2: Int = 0, val spo2Time: Long = 0L,
    val tempC: Double = 0.0, val tempTime: Long = 0L,
    /** nazwa urządzenia, z którego przyszło ostatnie tętno */
    val hrSource: String = ""
)

data class SensorSnap(val hr: Int, val power: Int, val cad: Int)

/** Odczyt bez zerowania średniej mocy (do powiadomień i UI). */
data class SensorPeek(val hr: Int, val power: Int, val cad: Int, val spo2: Int, val hrSource: String)

/** Dane z maszyny FTMS (bieżnia), jeśli świeże. */
data class MachineLive(val speedMs: Double, val inclinePct: Double)

/**
 * Urządzenia rozpoznawane po nazwie. Większość zegarków i opasek (Garmin, Polar, Suunto, Coros, Amazfit, Xiaomi,
 * Huawei/Honor, Wahoo, Whoop…) udostępnia tętno standardowym profilem BLE po włączeniu „transmisji tętna”.
 * Część (np. CMF/Nothing, Samsung Galaxy Watch, Fitbit) tego nie robi – wtedy tętno da się dołączyć po treningu
 * z Health Connect (zegarek → aplikacja producenta → Health Connect → Tracko).
 */
object KnownDevices {
    private class Rule(val match: (String) -> Boolean, val hint: String)

    private val rules = listOf(
        Rule({ it.contains("cmf") || it.contains("nothing") || it.startsWith("watch 3 pro") || it.startsWith("watch pro") },
            "CMF / Nothing: zegarek nie wysyła tętna na żywo przez Bluetooth (stan na 2026). Tętno dołączy się po " +
                "treningu z Health Connect (Nothing X → Health Connect). Jeśli aktualizacja doda transmisję tętna, " +
                "pojawi się tu automatycznie jako zwykły czujnik."),
        Rule({ it.startsWith("galaxy watch") || it.contains("gear ") },
            "Samsung Galaxy Watch nie ma standardowej transmisji tętna – użyj Health Connect (Samsung Health)."),
        Rule({ it.startsWith("fitbit") || it.startsWith("charge ") || it.startsWith("versa") || it.startsWith("sense") },
            "Fitbit nie transmituje tętna przez Bluetooth – użyj Health Connect (aplikacja Fitbit)."),
        Rule({ it.startsWith("forerunner") || it.startsWith("fenix") || it.startsWith("venu") || it.startsWith("vivoactive") ||
            it.startsWith("instinct") || it.startsWith("epix") || it.startsWith("edge") },
            "Garmin: w zegarku włącz Ustawienia → Czujniki → Nadawanie tętna (Broadcast HR)."),
        Rule({ it.startsWith("amazfit") || it.startsWith("mi band") || it.startsWith("mi smart band") || it.startsWith("xiaomi smart band") ||
            it.startsWith("redmi") },
            "Amazfit / Xiaomi: włącz w aplikacji Zepp / Mi Fitness „Udostępnianie tętna” (Heart rate sharing)."),
        Rule({ it.startsWith("huawei") || it.startsWith("honor") },
            "Huawei / Honor: włącz w zegarku „Transmisja danych tętna” (HR data broadcasts)."),
        Rule({ it.startsWith("polar") }, "Polar: zegarki – włącz „Udostępnij tętno innym urządzeniom” w menu treningu."),
        Rule({ it.startsWith("suunto") }, "Suunto: włącz „HR belt / Share HR” w ustawieniach zegarka."),
        Rule({ it.startsWith("coros") }, "Coros: włącz „Heart Rate Broadcast” w menu zegarka."),
        Rule({ it.startsWith("whoop") }, "Whoop: włącz „Heart Rate Broadcast” w aplikacji Whoop.")
    )

    fun hintFor(name: String): String? {
        val n = name.lowercase().trim()
        if (n.isEmpty()) return null
        return rules.firstOrNull { it.match(n) }?.hint
    }
}

private fun u8(v: ByteArray, o: Int): Int = v[o].toInt() and 0xFF
private fun u16(v: ByteArray, o: Int): Int = u8(v, o) or (u8(v, o + 1) shl 8)
private fun s16(v: ByteArray, o: Int): Int = u16(v, o).toShort().toInt()
private fun u32(v: ByteArray, o: Int): Long = u16(v, o).toLong() or (u16(v, o + 2).toLong() shl 16)

/**
 * Standardowe profile BLE: Heart Rate (180D), Cycling Power (1818), Cycling Speed & Cadence (1816),
 * Running Speed & Cadence (1814), Fitness Machine (1826: trenażery, bieżnie, wioślarze, orbitreki),
 * Pulse Oximeter (1822), Health Thermometer (1809), a także bateria (180F) i Device Information (180A).
 * Dzięki temu działa sprzęt praktycznie każdego producenta, nie tylko Garmin.
 */
@SuppressLint("MissingPermission")
object SensorHub {
    private val U_HR = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val U_HR_M = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val U_CSC = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
    private val U_CSC_M = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
    private val U_PWR = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
    private val U_PWR_M = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")
    private val U_BATT = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val U_BATT_L = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val U_CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    // bieg (Running Speed and Cadence)
    private val U_RSC = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
    private val U_RSC_M = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")
    // urządzenia fitness (FTMS): trenażer, bieżnia, wioślarz, orbitrek
    private val U_FTMS = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    private val U_FTMS_TREAD = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb")
    private val U_FTMS_CROSS = UUID.fromString("00002ace-0000-1000-8000-00805f9b34fb")
    private val U_FTMS_ROWER = UUID.fromString("00002ad1-0000-1000-8000-00805f9b34fb")
    private val U_FTMS_BIKE = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
    // pulsoksymetr (PLX): pomiar punktowy i ciągły
    private val U_PLX = UUID.fromString("00001822-0000-1000-8000-00805f9b34fb")
    private val U_PLX_SPOT = UUID.fromString("00002a5e-0000-1000-8000-00805f9b34fb")
    private val U_PLX_CONT = UUID.fromString("00002a5f-0000-1000-8000-00805f9b34fb")
    // termometr (Health Thermometer) – np. czujniki temperatury ciała
    private val U_TEMP = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb")
    private val U_TEMP_M = UUID.fromString("00002a1c-0000-1000-8000-00805f9b34fb")
    // informacje o urządzeniu
    private val U_DIS = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    private val U_DIS_MAKER = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    private val U_DIS_MODEL = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")

    private val KIND_BY_SERVICE: Map<UUID, SensorKind> by lazy {
        mapOf(
            U_HR to SensorKind.HR, U_PWR to SensorKind.POWER, U_CSC to SensorKind.CSC, U_RSC to SensorKind.RSC,
            U_FTMS to SensorKind.FTMS, U_PLX to SensorKind.SPO2, U_TEMP to SensorKind.TEMP
        )
    }

    private var appCtx: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val conns = LinkedHashMap<String, Conn>()
    private val lock = Any()
    private var pSum = 0L
    private var pCnt = 0

    val devices = MutableStateFlow<List<SensorInfo>>(emptyList())
    val found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    val live = MutableStateFlow(SensorLive())

    private class Conn(val address: String, var name: String) {
        var gatt: BluetoothGatt? = null
        var connected = false
        var connecting = false
        var wanted = true
        var battery = -1
        var maker = ""
        var model = ""
        var authRetries = 0
        var discovered = false
        val kinds = mutableSetOf<SensorKind>()
        val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
        var busy = false
        var lastWheelRevs = -1L
        var lastWheelTime = -1
        var lastWheelEvent = 0L
        var lastCrankRevs = -1
        var lastCrankTime = -1
        var lastCrankEvent = 0L
    }

    fun init(c: Context) {
        appCtx = c.applicationContext
    }

    // ---------- uprawnienia ----------

    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(): Boolean {
        val c = appCtx ?: return false
        return permissions().all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun adapter(): BluetoothAdapter? =
        (appCtx?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ---------- skanowanie ----------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = result.scanRecord?.deviceName ?: runCatching { dev.name }.getOrNull() ?: ""
            val kinds = mutableSetOf<SensorKind>()
            result.scanRecord?.serviceUuids?.forEach { u -> KIND_BY_SERVICE[u.uuid]?.let { kinds.add(it) } }
            val item = FoundDevice(dev.address, name.ifBlank { "Urządzenie BLE" }, result.rssi, kinds, KnownDevices.hintFor(name))
            found.update { list ->
                (list.filter { it.address != item.address } + item).sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning.value = false
        }
    }

    fun startScan(all: Boolean) {
        val sc = adapter()?.bluetoothLeScanner ?: return
        found.value = emptyList()
        val filters = if (all) emptyList() else KIND_BY_SERVICE.keys.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            sc.startScan(filters, settings, scanCb)
            scanning.value = true
        } catch (e: Exception) {
            scanning.value = false
            return
        }
        handler.postDelayed({ stopScan() }, 20000)
    }

    fun stopScan() {
        try {
            adapter()?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (e: Exception) {
        }
        scanning.value = false
    }

    // ---------- połączenia ----------

    fun connectSaved() {
        if (!hasPermissions()) return
        for (line in Prefs.savedSensors.lines()) {
            val parts = line.split("|", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) connect(parts[0], parts[1])
        }
    }

    /** Połącz i zapamiętaj czujnik. */
    fun add(address: String, name: String) {
        connect(address, name)
    }

    fun connect(address: String, name: String) {
        handler.post {
            val c = conns.getOrPut(address) { Conn(address, name) }
            c.wanted = true
            persist()
            if (c.connected || c.connecting) {
                publish()
                return@post
            }
            try {
                val dev = adapter()?.getRemoteDevice(address)
                if (dev == null) {
                    publish()
                    return@post
                }
                c.connecting = true
                publish()
                c.gatt = dev.connectGatt(appCtx, false, GattCb(c), BluetoothDevice.TRANSPORT_LE)
            } catch (e: Exception) {
                c.connecting = false
                publish()
            }
        }
    }

    fun forget(address: String) {
        handler.post {
            val c = conns.remove(address)
            if (c != null) {
                c.wanted = false
                c.gatt?.let {
                    try { it.disconnect(); it.close() } catch (e: Exception) { }
                }
                c.gatt = null
            }
            persist()
            publish()
        }
    }

    private fun persist() {
        Prefs.savedSensors = conns.values.filter { it.wanted }
            .joinToString("\n") { it.address + "|" + it.name.replace("|", " ").replace("\n", " ") }
    }

    private fun publish() {
        devices.value = conns.values.map {
            SensorInfo(
                it.address, it.name, it.connected, it.connecting, it.battery, it.kinds.toSet(),
                listOf(it.maker, it.model).filter { m -> m.isNotBlank() }.joinToString(" "),
                if (it.connected && it.discovered && it.kinds.isEmpty()) KnownDevices.hintFor(it.name)
                    ?: "Urządzenie nie udostępnia żadnego standardowego profilu sportowego (tętno, moc, kadencja…)."
                else if (!it.connected) KnownDevices.hintFor(it.name) else null
            )
        }
    }

    private fun onDisconnected(c: Conn, gatt: BluetoothGatt) {
        c.connected = false
        c.connecting = false
        c.ops.clear()
        c.busy = false
        c.lastWheelRevs = -1L
        c.lastCrankRevs = -1
        c.authRetries = 0
        c.discovered = false
        try { gatt.close() } catch (e: Exception) { }
        if (c.gatt === gatt) c.gatt = null
        publish()
        if (c.wanted && conns[c.address] === c) {
            handler.postDelayed({ connect(c.address, c.name) }, 5000)
        }
    }

    private fun setupServices(c: Conn, gatt: BluetoothGatt) {
        c.kinds.clear()
        c.ops.clear()
        c.busy = false
        c.discovered = true
        fun sub(service: UUID, ch: UUID, kind: SensorKind) {
            gatt.getService(service)?.getCharacteristic(ch)?.let { x ->
                c.kinds.add(kind)
                c.ops.addLast { g -> enableNotify(g, x) }
            }
        }
        sub(U_HR, U_HR_M, SensorKind.HR)
        sub(U_PWR, U_PWR_M, SensorKind.POWER)
        sub(U_CSC, U_CSC_M, SensorKind.CSC)
        sub(U_RSC, U_RSC_M, SensorKind.RSC)
        for (m in listOf(U_FTMS_BIKE, U_FTMS_TREAD, U_FTMS_ROWER, U_FTMS_CROSS)) sub(U_FTMS, m, SensorKind.FTMS)
        // pulsoksymetr: wolimy pomiar ciągły; punktowy tylko, gdy ciągłego brak
        if (gatt.getService(U_PLX)?.getCharacteristic(U_PLX_CONT) != null) sub(U_PLX, U_PLX_CONT, SensorKind.SPO2)
        else sub(U_PLX, U_PLX_SPOT, SensorKind.SPO2)
        sub(U_TEMP, U_TEMP_M, SensorKind.TEMP)
        gatt.getService(U_BATT)?.getCharacteristic(U_BATT_L)?.let { ch ->
            c.ops.addLast { g -> g.readCharacteristic(ch) }
        }
        gatt.getService(U_DIS)?.let { dis ->
            dis.getCharacteristic(U_DIS_MAKER)?.let { ch -> c.ops.addLast { g -> g.readCharacteristic(ch) } }
            dis.getCharacteristic(U_DIS_MODEL)?.let { ch -> c.ops.addLast { g -> g.readCharacteristic(ch) } }
        }
        publish()
        next(c)
    }

    /** Operacje GATT muszą iść po kolei: kolejna startuje po zakończeniu poprzedniej. */
    private fun next(c: Conn) {
        if (c.busy) return
        val g = c.gatt ?: return
        val op = c.ops.removeFirstOrNull() ?: return
        c.busy = true
        val started = try { op(g) } catch (e: Exception) { false }
        if (!started) {
            c.busy = false
            next(c)
        }
    }

    /** Włącza powiadomienia (notify) albo – gdy charakterystyka ma tylko indicate (termometr, część FTMS) – wskazania. */
    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(U_CCC) ?: return false
        val indicateOnly = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
            (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        val value = if (indicateOnly) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    /**
     * Część zegarków (Polar, Huawei, Amazfit…) wymaga sparowania, zanim pozwoli włączyć powiadomienia o tętnie.
     * Przy błędzie autoryzacji prosimy o parowanie i ponawiamy konfigurację po kilku sekundach.
     */
    private fun onAuthError(c: Conn, gatt: BluetoothGatt) {
        if (c.authRetries >= 3) return
        c.authRetries++
        runCatching { if (gatt.device.bondState == BluetoothDevice.BOND_NONE) gatt.device.createBond() }
        handler.postDelayed({
            val g = c.gatt
            if (c.connected && g != null) setupServices(c, g)
        }, 6000L)
    }

    private class GattCb(private val c: Conn) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    c.connected = true
                    c.connecting = false
                    publish()
                    try { gatt.discoverServices() } catch (e: Exception) { }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onDisconnected(c, gatt)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post { setupServices(c, gatt) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                c.busy = false
                // 5 = INSUFFICIENT_AUTHENTICATION, 15 = INSUFFICIENT_ENCRYPTION, 137 = GATT_AUTH_FAIL
                if (status == 5 || status == 15 || status == 137) onAuthError(c, gatt) else next(c)
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val v = characteristic.value ?: return
            handleNotify(c, characteristic.uuid, v)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(c, characteristic.uuid, value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val v = characteristic.value ?: ByteArray(0)
            val id = characteristic.uuid
            handler.post { handleRead(c, id, v) }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val id = characteristic.uuid
            handler.post { handleRead(c, id, value) }
        }
    }

    private fun handleRead(c: Conn, uuid: UUID, v: ByteArray) {
        if (uuid == U_BATT_L && v.isNotEmpty()) {
            c.battery = u8(v, 0)
            publish()
        }
        if ((uuid == U_DIS_MAKER || uuid == U_DIS_MODEL) && v.isNotEmpty()) {
            val txt = String(v, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
            if (uuid == U_DIS_MAKER) c.maker = txt else c.model = txt
            publish()
        }
        c.busy = false
        next(c)
    }

    // ---------- parsowanie powiadomień ----------

    private fun handleNotify(c: Conn, uuid: UUID, v: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        when (uuid) {
            U_HR_M -> {
                val hr = parseHr(v)
                if (hr in 1..250) live.update { it.copy(hr = hr, hrTime = now, hrSource = c.name) }
            }
            U_PWR_M -> parsePower(c, v, now)
            U_CSC_M -> parseCsc(c, v, now)
            U_RSC_M -> parseRsc(v, now)
            U_FTMS_BIKE, U_FTMS_TREAD, U_FTMS_ROWER, U_FTMS_CROSS -> {
                val m = BleParse.ftms(uuid.toString().substring(4, 8), v) ?: return
                applyMachine(c, m, now)
            }
            U_PLX_CONT, U_PLX_SPOT -> {
                val (spo2, pr) = BleParse.plx(v) ?: return
                live.update { l ->
                    var n = l
                    if (spo2 in 50..100) n = n.copy(spo2 = spo2, spo2Time = now)
                    // tętno z pulsoksymetru tylko, gdy nie ma świeżego z pasa/zegarka
                    if (pr in 30..250 && now - l.hrTime > 3000) n = n.copy(hr = pr, hrTime = now, hrSource = c.name)
                    n
                }
            }
            U_TEMP_M -> {
                val t = BleParse.temperatureC(v) ?: return
                if (t in 30.0..45.0) live.update { it.copy(tempC = t, tempTime = now) }
            }
            else -> {}
        }
    }

    private fun parseHr(v: ByteArray): Int {
        if (v.size < 2) return 0
        val flags = u8(v, 0)
        return if ((flags and 0x01) == 0) u8(v, 1) else if (v.size >= 3) u16(v, 1) else 0
    }

    private fun parsePower(c: Conn, v: ByteArray, now: Long) {
        if (v.size < 4) return
        val flags = u16(v, 0)
        val p = s16(v, 2).coerceAtLeast(0)
        synchronized(lock) { pSum += p; pCnt++ }
        live.update { it.copy(power = p, powerTime = now) }
        var off = 4
        if ((flags and 0x01) != 0) off += 1   // pedal power balance
        if ((flags and 0x04) != 0) off += 2   // accumulated torque
        if ((flags and 0x10) != 0) off += 6   // wheel revolution data
        if ((flags and 0x20) != 0 && v.size >= off + 4) {
            updateCadence(c, u16(v, off), u16(v, off + 2), now)
        }
    }

    private fun parseCsc(c: Conn, v: ByteArray, now: Long) {
        if (v.isEmpty()) return
        val flags = u8(v, 0)
        var off = 1
        if ((flags and 0x01) != 0 && v.size >= off + 6) {
            updateWheel(c, u32(v, off), u16(v, off + 4), now)
            off += 6
        }
        if ((flags and 0x02) != 0 && v.size >= off + 4) {
            updateCadence(c, u16(v, off), u16(v, off + 2), now)
        }
    }

    private fun updateWheel(c: Conn, revs: Long, time: Int, now: Long) {
        if (c.lastWheelRevs >= 0) {
            var dR = revs - c.lastWheelRevs
            if (dR < 0) dR += 0x100000000L
            var dT = time - c.lastWheelTime
            if (dT < 0) dT += 65536
            if (dT > 0) {
                val speed = dR * (Prefs.wheelMm / 1000.0) / (dT / 1024.0)
                if (speed < 30.0) live.update { it.copy(speedMs = speed, speedTime = now) }
                c.lastWheelEvent = now
            } else if (now - c.lastWheelEvent > 2500) {
                live.update { it.copy(speedMs = 0.0, speedTime = now) }
            }
        }
        c.lastWheelRevs = revs
        c.lastWheelTime = time
    }

    private fun updateCadence(c: Conn, revs: Int, time: Int, now: Long) {
        if (c.lastCrankRevs >= 0) {
            var dR = revs - c.lastCrankRevs
            if (dR < 0) dR += 65536
            var dT = time - c.lastCrankTime
            if (dT < 0) dT += 65536
            if (dT > 0) {
                val rpm = (dR * 60.0 * 1024.0 / dT).toInt()
                if (rpm in 0..250) live.update { it.copy(cadence = rpm, cadTime = now) }
                c.lastCrankEvent = now
            } else if (now - c.lastCrankEvent > 2500) {
                live.update { it.copy(cadence = 0, cadTime = now) }
            }
        }
        c.lastCrankRevs = revs
        c.lastCrankTime = time
    }

    private fun parseRsc(v: ByteArray, now: Long) {
        val r = BleParse.rsc(v) ?: return
        live.update { it.copy(runSpeedMs = r.first, runCadence = r.second, runTime = now) }
    }

    private fun applyMachine(c: Conn, m: BleParse.Machine, now: Long) {
        if (m.power != null && m.power >= 0) synchronized(lock) { pSum += m.power; pCnt++ }
        live.update { l ->
            var n = l.copy(machineTime = now)
            if (m.speedMs != null) n = n.copy(machineSpeedMs = m.speedMs)
            if (m.inclinePct != null) n = n.copy(machineIncline = m.inclinePct)
            if (m.cadence != null && m.cadence in 0..250) n = n.copy(cadence = m.cadence, cadTime = now)
            if (m.power != null && m.power >= 0) n = n.copy(power = m.power, powerTime = now)
            if (m.hr != null && m.hr in 30..250 && now - l.hrTime > 3000) n = n.copy(hr = m.hr, hrTime = now, hrSource = c.name)
            n
        }
    }

    /** Bieżnia FTMS: bieżąca prędkość taśmy i nachylenie (null = brak świeżych danych). */
    fun machine(): MachineLive? {
        val l = live.value
        if (SystemClock.elapsedRealtime() - l.machineTime > 5000) return null
        return MachineLive(l.machineSpeedMs, if (l.machineIncline.isNaN()) Double.NaN else l.machineIncline)
    }

    /** Odczyt bez zerowania średniej mocy – do powiadomień i podglądu. */
    fun peek(): SensorPeek {
        val now = SystemClock.elapsedRealtime()
        val l = live.value
        val cad = when {
            now - l.cadTime < 5000 -> l.cadence
            now - l.runTime < 5000 -> l.runCadence
            else -> 0
        }
        return SensorPeek(
            hr = if (now - l.hrTime < 5000) l.hr else 0,
            power = if (now - l.powerTime < 5000) l.power else 0,
            cad = cad,
            spo2 = if (now - l.spo2Time < 30000) l.spo2 else 0,
            hrSource = l.hrSource
        )
    }

    /** Migawka dla punktu GPS: tętno, średnia moc od poprzedniego punktu, kadencja (0 gdy dane przeterminowane). */
    fun snapshot(): SensorSnap {
        val now = SystemClock.elapsedRealtime()
        val l = live.value
        val hr = if (now - l.hrTime < 5000) l.hr else 0
        // kadencja: rower (CSC/moc/FTMS) albo bieg (RSC, kroki/min)
        val cad = when {
            now - l.cadTime < 5000 -> l.cadence
            now - l.runTime < 5000 -> l.runCadence
            else -> 0
        }
        val pw = synchronized(lock) {
            val p = if (pCnt > 0) (pSum / pCnt).toInt() else 0
            pSum = 0
            pCnt = 0
            p
        }
        return SensorSnap(hr, pw, cad)
    }
}
