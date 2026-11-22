package pl.trailtrack

import kotlin.math.pow

/**
 * Parsery standardowych charakterystyk Bluetooth SIG (bez zależności od Androida – testowalne na JVM).
 * Każdy zwraca null przy zbyt krótkiej / niepoprawnej ramce.
 */
object BleParse {
    private fun u8(v: ByteArray, o: Int): Int = v[o].toInt() and 0xFF
    private fun u16(v: ByteArray, o: Int): Int = u8(v, o) or (u8(v, o + 1) shl 8)
    private fun s16(v: ByteArray, o: Int): Int = u16(v, o).toShort().toInt()

    /** Dane z maszyny FTMS; null w polu = maszyna go nie podaje. */
    class Machine(
        val speedMs: Double? = null,
        val inclinePct: Double? = null,
        val cadence: Int? = null,
        val power: Int? = null,
        val hr: Int? = null
    )

    /**
     * Running Speed and Cadence Measurement (0x2A53): prędkość (1/256 m/s) i kadencja (kroki/min).
     * Zwraca (prędkość m/s, kadencja).
     */
    fun rsc(v: ByteArray): Pair<Double, Int>? {
        if (v.size < 4) return null
        val speed = u16(v, 1) / 256.0
        val cad = u8(v, 3)
        if (speed > 15.0) return null
        return speed to cad
    }

    /**
     * Fitness Machine: [type] = skrócony UUID charakterystyki ("2ad2" rower, "2acd" bieżnia, "2ad1" wioślarz,
     * "2ace" orbitrek). Pola czytamy wg flag z FTMS 1.0; nieobsłużone pole przerywa parsowanie (zwracamy to, co mamy).
     */
    fun ftms(type: String, v: ByteArray): Machine? = runCatching {
        when (type.lowercase()) {
            "2ad2" -> indoorBike(v)
            "2acd" -> treadmill(v)
            "2ad1" -> rower(v)
            "2ace" -> crossTrainer(v)
            else -> null
        }
    }.getOrNull()

    private class Reader(val v: ByteArray, var o: Int) {
        fun has(n: Int) = o + n <= v.size
        fun u8(): Int { val r = v[o].toInt() and 0xFF; o += 1; return r }
        fun u16(): Int { val r = (v[o].toInt() and 0xFF) or ((v[o + 1].toInt() and 0xFF) shl 8); o += 2; return r }
        fun s16(): Int = u16().toShort().toInt()
        fun skip(n: Int) { o += n }
    }

    private fun indoorBike(v: ByteArray): Machine? {
        if (v.size < 2) return null
        val f = u16(v, 0)
        val r = Reader(v, 2)
        var speed: Double? = null
        var cad: Int? = null
        var pw: Int? = null
        var hr: Int? = null
        if (f and 0x0001 == 0) { if (!r.has(2)) return null; speed = r.u16() / 100.0 / 3.6 }  // „More Data” = 0 → jest prędkość
        if (f and 0x0002 != 0) r.skip(2)                                   // średnia prędkość
        if (f and 0x0004 != 0) { if (!r.has(2)) return Machine(speed); cad = r.u16() / 2 }
        if (f and 0x0008 != 0) r.skip(2)                                   // średnia kadencja
        if (f and 0x0010 != 0) r.skip(3)                                   // dystans
        if (f and 0x0020 != 0) r.skip(2)                                   // poziom oporu
        if (f and 0x0040 != 0) { if (!r.has(2)) return Machine(speed, cadence = cad); pw = r.s16() }
        if (f and 0x0080 != 0) r.skip(2)                                   // średnia moc
        if (f and 0x0100 != 0) r.skip(5)                                   // energia
        if (f and 0x0200 != 0 && r.has(1)) hr = r.u8()
        return Machine(speedMs = speed, cadence = cad, power = pw, hr = hr?.takeIf { it > 0 })
    }

    private fun treadmill(v: ByteArray): Machine? {
        if (v.size < 2) return null
        val f = u16(v, 0)
        val r = Reader(v, 2)
        var speed: Double? = null
        var incl: Double? = null
        var hr: Int? = null
        var pw: Int? = null
        if (f and 0x0001 == 0) { if (!r.has(2)) return null; speed = r.u16() / 100.0 / 3.6 }
        if (f and 0x0002 != 0) r.skip(2)                                   // średnia prędkość
        if (f and 0x0004 != 0) r.skip(3)                                   // dystans
        if (f and 0x0008 != 0) {                                           // nachylenie (0,1 %) + kąt rampy
            if (!r.has(4)) return Machine(speed)
            val raw = r.s16()
            r.skip(2)
            if (raw != 0x7FFF) incl = raw / 10.0
        }
        if (f and 0x0010 != 0) r.skip(4)                                   // przewyższenie +/−
        if (f and 0x0020 != 0) r.skip(1)                                   // tempo chwilowe
        if (f and 0x0040 != 0) r.skip(1)                                   // tempo średnie
        if (f and 0x0080 != 0) r.skip(5)                                   // energia
        if (f and 0x0100 != 0 && r.has(1)) hr = r.u8()
        if (f and 0x0200 != 0) r.skip(1)                                   // MET
        if (f and 0x0400 != 0) r.skip(2)                                   // czas
        if (f and 0x0800 != 0) r.skip(2)                                   // pozostały czas
        if (f and 0x1000 != 0 && r.has(4)) { r.skip(2); pw = r.s16() }     // siła na taśmie + moc
        return Machine(speedMs = speed, inclinePct = incl, hr = hr?.takeIf { it > 0 }, power = pw)
    }

    private fun rower(v: ByteArray): Machine? {
        if (v.size < 2) return null
        val f = u16(v, 0)
        val r = Reader(v, 2)
        var rate: Int? = null
        var speed: Double? = null
        var pw: Int? = null
        var hr: Int? = null
        if (f and 0x0001 == 0) { if (!r.has(3)) return null; rate = r.u8() / 2; r.skip(2) }   // tempo wiosłowania + liczba pociągnięć
        if (f and 0x0002 != 0) r.skip(1)
        if (f and 0x0004 != 0) r.skip(3)
        if (f and 0x0008 != 0) {                                           // tempo na 500 m (s)
            if (!r.has(2)) return Machine(cadence = rate)
            val p = r.u16()
            if (p in 1..3599) speed = 500.0 / p
        }
        if (f and 0x0010 != 0) r.skip(2)
        if (f and 0x0020 != 0) { if (!r.has(2)) return Machine(speed, cadence = rate); pw = r.s16() }
        if (f and 0x0040 != 0) r.skip(2)
        if (f and 0x0080 != 0) r.skip(2)
        if (f and 0x0100 != 0) r.skip(5)
        if (f and 0x0200 != 0 && r.has(1)) hr = r.u8()
        return Machine(speedMs = speed, cadence = rate, power = pw, hr = hr?.takeIf { it > 0 })
    }

    private fun crossTrainer(v: ByteArray): Machine? {
        if (v.size < 3) return null
        val f = u8(v, 0) or (u8(v, 1) shl 8) or (u8(v, 2) shl 16)        // 24-bitowe flagi
        val r = Reader(v, 3)
        var speed: Double? = null
        var cad: Int? = null
        var pw: Int? = null
        var hr: Int? = null
        var incl: Double? = null
        if (f and 0x0001 == 0) { if (!r.has(2)) return null; speed = r.u16() / 100.0 / 3.6 }
        if (f and 0x0002 != 0) r.skip(2)
        if (f and 0x0004 != 0) r.skip(3)
        if (f and 0x0008 != 0) { if (!r.has(4)) return Machine(speed); cad = r.u16(); r.skip(2) }   // kroki/min
        if (f and 0x0010 != 0) r.skip(2)
        if (f and 0x0020 != 0) r.skip(4)
        if (f and 0x0040 != 0) { if (!r.has(4)) return Machine(speed, cadence = cad); val raw = r.s16(); r.skip(2); if (raw != 0x7FFF) incl = raw / 10.0 }
        if (f and 0x0080 != 0) r.skip(2)
        if (f and 0x0100 != 0) { if (!r.has(2)) return Machine(speed, incl, cad); pw = r.s16() }
        if (f and 0x0200 != 0) r.skip(2)
        if (f and 0x0400 != 0) r.skip(5)
        if (f and 0x0800 != 0 && r.has(1)) hr = r.u8()
        return Machine(speedMs = speed, inclinePct = incl, cadence = cad, power = pw, hr = hr?.takeIf { it > 0 })
    }

    /** IEEE-11073 SFLOAT (16 bitów: 4-bitowy wykładnik, 12-bitowa mantysa); null dla wartości specjalnych. */
    fun sfloat(raw: Int): Double? {
        val r = raw and 0xFFFF
        if (r == 0x07FF || r == 0x0800 || r == 0x07FE || r == 0x0802 || r == 0x0801) return null
        var m = r and 0x0FFF
        if (m >= 0x0800) m -= 0x1000
        var e = (r shr 12) and 0x0F
        if (e >= 0x08) e -= 0x10
        return m * 10.0.pow(e)
    }

    /** IEEE-11073 FLOAT (32 bity: 8-bitowy wykładnik, 24-bitowa mantysa). */
    fun float32(v: ByteArray, o: Int): Double? {
        if (v.size < o + 4) return null
        var m = u8(v, o) or (u8(v, o + 1) shl 8) or (u8(v, o + 2) shl 16)
        if (m == 0x7FFFFF || m == 0x800000 || m == 0x7FFFFE || m == 0x800002 || m == 0x800001) return null
        if (m >= 0x800000) m -= 0x1000000
        val e = v[o + 3].toInt()   // bajt ze znakiem
        return m * 10.0.pow(e)
    }

    /** PLX Spot-check (0x2A5E) / Continuous (0x2A5F): (SpO₂ %, tętno). */
    fun plx(v: ByteArray): Pair<Int, Int>? {
        if (v.size < 5) return null
        val spo2 = sfloat(u16(v, 1)) ?: return null
        val pr = sfloat(u16(v, 3)) ?: 0.0
        return Math.round(spo2).toInt() to Math.round(pr).toInt()
    }

    /** Temperature Measurement (0x2A1C) w °C (przelicza z °F, jeśli urządzenie tak podaje). */
    fun temperatureC(v: ByteArray): Double? {
        if (v.size < 5) return null
        val t = float32(v, 1) ?: return null
        return if ((u8(v, 0) and 0x01) != 0) (t - 32.0) * 5.0 / 9.0 else t
    }
}
