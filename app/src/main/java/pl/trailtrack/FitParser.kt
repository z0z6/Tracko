package pl.trailtrack

/** Minimalny dekoder plików FIT (Garmin Edge/Forerunner): rekordy, okrążenia, zdarzenia timera. */
private const val FIT_EPOCH = 631065600L
private const val NONE = Long.MIN_VALUE

class FitResult(val points: List<TrackPoint>, val laps: List<Int>)

private class FitDef(
    val big: Boolean,
    val global: Int,
    val nums: IntArray,
    val sizes: IntArray,
    val devBytes: Int
) {
    val total: Int = sizes.sum() + devBytes
}

private fun rd(b: ByteArray, p: Int, size: Int, big: Boolean): Long {
    var v = 0L
    if (big) {
        for (i in 0 until size) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
    } else {
        for (i in size - 1 downTo 0) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
    }
    return v
}

fun parseFit(b: ByteArray): FitResult {
    require(b.size > 14) { "Plik jest za krótki" }
    val hs = b[0].toInt() and 0xFF
    require(hs >= 12 && b.size > hs + 2 && String(b, 8, 4, Charsets.US_ASCII) == ".FIT") { "To nie jest plik FIT" }
    val dataSize = rd(b, 4, 4, false).toInt()
    val end = minOf(b.size - 2, hs + dataSize)

    var pos = hs
    val defs = arrayOfNulls<FitDef>(16)
    val pts = ArrayList<TrackPoint>()
    val lapTimes = ArrayList<Long>()
    var lastTs = 0L
    var needBreak = false
    var stopped = false
    var lastEle = 0.0

    while (pos < end) {
        val h = b[pos++].toInt() and 0xFF
        val compressed = (h and 0x80) != 0
        if (!compressed && (h and 0x40) != 0) {
            // definicja
            val local = h and 0x0F
            val hasDev = (h and 0x20) != 0
            pos += 1
            val big = b[pos].toInt() == 1
            pos += 1
            val global = rd(b, pos, 2, big).toInt()
            pos += 2
            val nf = b[pos++].toInt() and 0xFF
            val nums = IntArray(nf)
            val sizes = IntArray(nf)
            for (k in 0 until nf) {
                nums[k] = b[pos].toInt() and 0xFF
                sizes[k] = b[pos + 1].toInt() and 0xFF
                pos += 3
            }
            var dev = 0
            if (hasDev) {
                val nd = b[pos++].toInt() and 0xFF
                for (k in 0 until nd) {
                    dev += b[pos + 1].toInt() and 0xFF
                    pos += 3
                }
            }
            defs[local] = FitDef(big, global, nums, sizes, dev)
        } else {
            val local = if (compressed) (h shr 5) and 0x03 else h and 0x0F
            val d = defs[local] ?: throw IllegalStateException("Brak definicji wiadomości FIT")
            var ts = NONE
            if (compressed) {
                val off = (h and 0x1F).toLong()
                var t = (lastTs and 0x1FL.inv()) + off
                if (off < (lastTs and 0x1FL)) t += 32
                ts = t
                lastTs = t
            }
            var lat = NONE; var lon = NONE; var alt = NONE; var enhAlt = NONE
            var hr = NONE; var cad = NONE; var pw = NONE; var spd = NONE; var enhSpd = NONE
            var evt = NONE; var evtType = NONE

            var p = pos
            for (k in d.nums.indices) {
                val sz = d.sizes[k]
                val num = d.nums[k]
                if (sz in 1..4) {
                    val v = rd(b, p, sz, d.big)
                    if (num == 253) {
                        ts = v
                    } else if (d.global == 20) {
                        when (num) {
                            0 -> lat = v
                            1 -> lon = v
                            2 -> alt = v
                            3 -> hr = v
                            4 -> cad = v
                            6 -> spd = v
                            7 -> pw = v
                            73 -> enhSpd = v
                            78 -> enhAlt = v
                        }
                    } else if (d.global == 21) {
                        when (num) {
                            0 -> evt = v
                            1 -> evtType = v
                        }
                    }
                }
                p += sz
            }
            pos += d.total

            val tsValid = ts != NONE && ts != 0xFFFFFFFFL
            if (tsValid) lastTs = ts

            when (d.global) {
                20 -> {
                    if (tsValid && lat != NONE && lon != NONE && lat != 0x7FFFFFFFL && lon != 0x7FFFFFFFL) {
                        val la = lat.toInt() * (180.0 / 2147483648.0)
                        val lo = lon.toInt() * (180.0 / 2147483648.0)
                        val ele = when {
                            enhAlt != NONE && enhAlt != 0xFFFFFFFFL -> enhAlt / 5.0 - 500.0
                            alt != NONE && alt != 0xFFFFL -> alt / 5.0 - 500.0
                            else -> lastEle
                        }
                        lastEle = ele
                        val speed = when {
                            enhSpd != NONE && enhSpd != 0xFFFFFFFFL -> enhSpd / 1000.0
                            spd != NONE && spd != 0xFFFFL -> spd / 1000.0
                            else -> 0.0
                        }
                        val timeMs = (ts + FIT_EPOCH) * 1000L
                        if (pts.isEmpty() || pts.last().time != timeMs) {
                            pts.add(
                                TrackPoint(
                                    la, lo, ele, timeMs, speed, Terrain.ASPHALT,
                                    brk = needBreak && pts.isNotEmpty(),
                                    hr = if (hr != NONE && hr != 0xFFL) hr.toInt() else 0,
                                    power = if (pw != NONE && pw != 0xFFFFL) pw.toInt() else 0,
                                    cad = if (cad != NONE && cad != 0xFFL) cad.toInt() else 0
                                )
                            )
                            needBreak = false
                        }
                    }
                }
                21 -> {
                    if (evt == 0L) {
                        if (evtType == 1L || evtType == 4L || evtType == 8L || evtType == 9L) {
                            stopped = true
                        } else if (evtType == 0L && stopped) {
                            needBreak = true
                            stopped = false
                        }
                    }
                }
                19 -> {
                    if (tsValid) lapTimes.add((ts + FIT_EPOCH) * 1000L)
                }
            }
        }
    }

    val laps = ArrayList<Int>()
    if (lapTimes.size > 1) {
        for (t in lapTimes.dropLast(1)) {
            val idx = pts.indexOfFirst { it.time >= t }
            if (idx in 1 until pts.size - 1 && (laps.lastOrNull() ?: 0) < idx) laps.add(idx)
        }
    }
    return FitResult(pts, laps)
}
