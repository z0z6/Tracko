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
    CSC("🔄", "Prędkość/kadencja")
}

data class SensorInfo(
    val address: String,
    val name: String,
    val connected: Boolean,
    val connecting: Boolean,
    val battery: Int,
    val kinds: Set<SensorKind>
)

data class FoundDevice(val address: String, val name: String, val rssi: Int, val kinds: Set<SensorKind>)

data class SensorLive(
    val hr: Int = 0, val hrTime: Long = 0L,
    val power: Int = 0, val powerTime: Long = 0L,
    val cadence: Int = 0, val cadTime: Long = 0L,
    val speedMs: Double = 0.0, val speedTime: Long = 0L
)

data class SensorSnap(val hr: Int, val power: Int, val cad: Int)

private fun u8(v: ByteArray, o: Int): Int = v[o].toInt() and 0xFF
private fun u16(v: ByteArray, o: Int): Int = u8(v, o) or (u8(v, o + 1) shl 8)
private fun s16(v: ByteArray, o: Int): Int = u16(v, o).toShort().toInt()
private fun u32(v: ByteArray, o: Int): Long = u16(v, o).toLong() or (u16(v, o + 2).toLong() shl 16)

/** Standardowe profile BLE: Heart Rate (180D), Cycling Power (1818), Cycling Speed & Cadence (1816). */
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
            result.scanRecord?.serviceUuids?.forEach {
                when (it.uuid) {
                    U_HR -> kinds.add(SensorKind.HR)
                    U_PWR -> kinds.add(SensorKind.POWER)
                    U_CSC -> kinds.add(SensorKind.CSC)
                    else -> {}
                }
            }
            val item = FoundDevice(dev.address, name.ifBlank { "Urządzenie BLE" }, result.rssi, kinds)
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
        val filters = if (all) emptyList() else listOf(U_HR, U_PWR, U_CSC).map {
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
            SensorInfo(it.address, it.name, it.connected, it.connecting, it.battery, it.kinds.toSet())
        }
    }

    private fun onDisconnected(c: Conn, gatt: BluetoothGatt) {
        c.connected = false
        c.connecting = false
        c.ops.clear()
        c.busy = false
        c.lastWheelRevs = -1L
        c.lastCrankRevs = -1
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
        gatt.getService(U_HR)?.getCharacteristic(U_HR_M)?.let { ch ->
            c.kinds.add(SensorKind.HR)
            c.ops.addLast { g -> enableNotify(g, ch) }
        }
        gatt.getService(U_PWR)?.getCharacteristic(U_PWR_M)?.let { ch ->
            c.kinds.add(SensorKind.POWER)
            c.ops.addLast { g -> enableNotify(g, ch) }
        }
        gatt.getService(U_CSC)?.getCharacteristic(U_CSC_M)?.let { ch ->
            c.kinds.add(SensorKind.CSC)
            c.ops.addLast { g -> enableNotify(g, ch) }
        }
        gatt.getService(U_BATT)?.getCharacteristic(U_BATT_L)?.let { ch ->
            c.ops.addLast { g -> g.readCharacteristic(ch) }
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

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(U_CCC) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
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
            handler.post { c.busy = false; next(c) }
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
        c.busy = false
        next(c)
    }

    // ---------- parsowanie powiadomień ----------

    private fun handleNotify(c: Conn, uuid: UUID, v: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        when (uuid) {
            U_HR_M -> {
                val hr = parseHr(v)
                live.update { it.copy(hr = hr, hrTime = now) }
            }
            U_PWR_M -> parsePower(c, v, now)
            U_CSC_M -> parseCsc(c, v, now)
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

    /** Migawka dla punktu GPS: tętno, średnia moc od poprzedniego punktu, kadencja (0 gdy dane przeterminowane). */
    fun snapshot(): SensorSnap {
        val now = SystemClock.elapsedRealtime()
        val l = live.value
        val hr = if (now - l.hrTime < 5000) l.hr else 0
        val cad = if (now - l.cadTime < 5000) l.cadence else 0
        val pw = synchronized(lock) {
            val p = if (pCnt > 0) (pSum / pCnt).toInt() else 0
            pSum = 0
            pCnt = 0
            p
        }
        return SensorSnap(hr, pw, cad)
    }
}
