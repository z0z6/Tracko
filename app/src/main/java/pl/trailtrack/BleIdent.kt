package pl.trailtrack

import android.content.Context

/**
 * Rozpoznawanie urządzeń Bluetooth LE przed połączeniem – na podstawie samej reklamy (advertising):
 * nazwa (pełna lub skrócona), wygląd (GAP Appearance: zegarek, pas tętna, czujnik rowerowy…), producent
 * (identyfikator firmy z danych producenta) i ogłaszane usługi. Wszystkie urządzenia traktujemy jednakowo –
 * zegarek, opaska, pas czy czujnik dowolnej marki łączy się tak samo, przez standardowe profile.
 */
object BleIdent {
    class Ad(
        val name: String?,
        val appearance: Int?,
        val companyId: Int?,
        val services16: Set<Int>
    )

    /** Parsuje surowe dane reklamy (AD structures: długość, typ, dane). Odporne na uszkodzone ramki. */
    fun parse(raw: ByteArray?): Ad {
        var full: String? = null
        var short: String? = null
        var app: Int? = null
        var company: Int? = null
        val svc = HashSet<Int>()
        if (raw != null) {
            var i = 0
            while (i < raw.size) {
                val len = raw[i].toInt() and 0xFF
                if (len == 0) break
                if (i + 1 + len > raw.size) break
                val type = raw[i + 1].toInt() and 0xFF
                val from = i + 2
                val n = len - 1
                when (type) {
                    0x09 -> full = text(raw, from, n)
                    0x08 -> short = text(raw, from, n)
                    0x19 -> if (n >= 2) app = u16(raw, from)
                    0xFF -> if (n >= 2) company = u16(raw, from)
                    0x02, 0x03 -> {
                        var k = 0
                        while (k + 1 < n) { svc.add(u16(raw, from + k)); k += 2 }
                    }
                }
                i += len + 1
            }
        }
        return Ad((full ?: short)?.takeIf { it.isNotBlank() }, app, company, svc)
    }

    private fun u16(v: ByteArray, o: Int): Int = (v[o].toInt() and 0xFF) or ((v[o + 1].toInt() and 0xFF) shl 8)

    private fun text(v: ByteArray, o: Int, n: Int): String? =
        runCatching { String(v, o, n, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' } }.getOrNull()

    /** Rodzaj urządzenia po polu Appearance (kategoria = bity 15..6, podkategoria = bity 5..0). */
    fun appearanceLabel(a: Int): String? {
        val cat = a shr 6
        val sub = a and 0x3F
        return when (cat) {
            1 -> "Telefon"
            2 -> if (sub == 6) "Komputer na nadgarstek" else "Komputer"
            3 -> when (sub) { 1 -> "Zegarek sportowy"; 2 -> "Smartwatch"; else -> "Zegarek" }
            4 -> "Zegar"
            6 -> "Pilot"
            7 -> "Okulary"
            8 -> "Lokalizator"
            12 -> "Termometr"
            13 -> if (sub == 1) "Pas tętna" else "Czujnik tętna"
            14 -> "Ciśnieniomierz"
            15 -> "Klawiatura / mysz / kontroler"
            16 -> "Glukometr"
            17 -> "Czujnik biegowy"
            18 -> when (sub) {
                1 -> "Komputer rowerowy"; 2 -> "Czujnik prędkości"; 3 -> "Czujnik kadencji"
                4 -> "Miernik mocy"; 5 -> "Czujnik prędkości i kadencji"; else -> "Czujnik rowerowy"
            }
            21 -> "Czujnik"
            33, 34, 37 -> "Słuchawki / audio"
            41 -> "Aparat słuchowy"
            49 -> "Pulsoksymetr"
            50 -> "Waga"
            52 -> "Monitor glukozy"
            81 -> "Urządzenie outdoor / GPS"
            else -> null
        }
    }

    /** Czy typ urządzenia sugeruje sprzęt noszony / sportowy (do sortowania listy). */
    fun isWearableOrSport(a: Int?): Boolean {
        val cat = (a ?: return false) shr 6
        return cat in setOf(3, 12, 13, 14, 17, 18, 49, 50, 81)
    }

    @Volatile private var companies: Map<Int, String>? = null

    /** Nazwa producenta wg identyfikatora Bluetooth SIG (lista z res/raw/bt_companies.txt). */
    fun company(ctx: Context?, id: Int): String? {
        val m = companies ?: synchronized(this) {
            companies ?: (if (ctx == null) return null else load(ctx).also { companies = it })
        }
        return m[id]
    }

    private fun load(ctx: Context): Map<Int, String> {
        return runCatching {
            val out = HashMap<Int, String>(4500)
            ctx.resources.openRawResource(R.raw.bt_companies).bufferedReader().useLines { lines ->
                for (l in lines) {
                    val t = l.indexOf('\t')
                    if (t <= 0) continue
                    val id = l.substring(0, t).toIntOrNull(16) ?: continue
                    out[id] = l.substring(t + 1)
                }
            }
            out
        }.getOrDefault(emptyMap())
    }
}
