package pl.trailtrack

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Kopia zapasowa wszystkich danych w jednym pliku (.zip):
 *  - manifest.json – format, wersja aplikacji i bazy, data, liczba aktywności,
 *  - tracko.db – cała baza (aktywności z punktami, okrążenia, trasy, odcinki i wyniki duchów),
 *  - settings.json – ustawienia (wygląd, czujniki, głos, cele, strefy…), bez tokenów logowania do konta online.
 * Mapy offline (często gigabajty) nie wchodzą do kopii.
 *
 * Import zastępuje bieżące dane (przedtem robi automatyczną kopię bieżących danych w pamięci aplikacji)
 * i uruchamia aplikację ponownie.
 */
object Backup {
    const val FORMAT = "tracko-backup"
    const val FORMAT_VERSION = 1
    private const val DB_NAME = "trailtrack.db"
    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_DB = "tracko.db"
    private const val ENTRY_SETTINGS = "settings.json"

    /** klucze ustawień, których nie przenosimy (sesja konta online – po przeniesieniu trzeba się zalogować ponownie) */
    private val SKIP_KEYS = setOf("cloudAccess", "cloudRefresh", "cloudExpiresAt")

    fun suggestedName(): String =
        "Tracko-kopia-" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".zip"

    class Info(val created: Long, val appVersion: String, val dbVersion: Int, val rides: Int)

    /** Zapis kopii do strumienia (np. pliku wybranego przez użytkownika). Zwraca liczbę aktywności. */
    suspend fun export(ctx: Context, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val db = AppDb.get(app)
        // zapis zaległych zmian z dziennika WAL do pliku bazy – kopia jest spójna
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val rides = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM rides WHERE finished = 1").use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val dbFile = app.getDatabasePath(DB_NAME)
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", FORMAT_VERSION)
            .put("created", System.currentTimeMillis())
            .put("appVersion", appVersion(app))
            .put("dbVersion", db.openHelper.readableDatabase.version)
            .put("rides", rides)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(ENTRY_DB))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(ENTRY_SETTINGS))
            zip.write(settingsJson(app).toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        rides
    }

    /** Kopia do pliku w pamięci aplikacji (np. automatyczna przed importem). */
    suspend fun exportToFile(ctx: Context, f: File): Int = FileOutputStream(f).use { export(ctx, it) }

    /**
     * Sprawdza plik kopii i rozpakowuje go do katalogu tymczasowego. Rzuca wyjątek z opisem po polsku,
     * gdy plik nie jest kopią Tracko albo pochodzi z nowszej wersji aplikacji.
     */
    suspend fun prepare(ctx: Context, uri: Uri): Pair<Info, File> = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val input: InputStream = ctx.contentResolver.openInputStream(uri) ?: error("Nie można otworzyć pliku")
        var manifest: JSONObject? = null
        input.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    // tylko znane nazwy – bez ścieżek z pliku (ochrona przed „zip slip”)
                    when (e.name) {
                        ENTRY_MANIFEST -> manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        ENTRY_DB -> File(dir, ENTRY_DB).outputStream().use { zip.copyTo(it) }
                        ENTRY_SETTINGS -> File(dir, ENTRY_SETTINGS).outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        val m = manifest ?: error("To nie jest kopia zapasowa Tracko (brak manifestu)")
        if (m.optString("format") != FORMAT) error("To nie jest kopia zapasowa Tracko")
        if (m.optInt("formatVersion") > FORMAT_VERSION) error("Kopia pochodzi z nowszej wersji Tracko – zaktualizuj aplikację")
        val dbf = File(dir, ENTRY_DB)
        if (!dbf.exists() || dbf.length() < 100) error("Kopia nie zawiera bazy danych")
        val header = dbf.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        if (String(header, Charsets.US_ASCII) != "SQLite format 3\u0000") error("Uszkodzona baza danych w kopii")
        val dbVersion = m.optInt("dbVersion")
        if (dbVersion > DB_VERSION) error("Kopia pochodzi z nowszej wersji Tracko (baza v$dbVersion) – zaktualizuj aplikację")
        Info(m.optLong("created"), m.optString("appVersion"), dbVersion, m.optInt("rides")) to dir
    }

    /**
     * Zastępuje bieżące dane danymi z kopii (po [prepare]) i uruchamia aplikację ponownie.
     * Bieżące dane zapisuje wcześniej do files/backups/przed-importem-….zip.
     */
    suspend fun restore(ctx: Context, dir: File) {
        val app = ctx.applicationContext
        withContext(Dispatchers.IO) {
            val safety = File(app.filesDir, "backups").apply { mkdirs() }
            // trzymamy 3 ostatnie automatyczne kopie
            safety.listFiles()?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
            runCatching { exportToFile(app, File(safety, "przed-importem-" + suggestedName())) }

            AppDb.closeForRestore()
            val target = app.getDatabasePath(DB_NAME).also { it.parentFile?.mkdirs() }
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(target.path + suffix).delete()
            File(dir, ENTRY_DB).copyTo(target, overwrite = true)
            File(dir, ENTRY_SETTINGS).takeIf { it.exists() }?.let { applySettings(app, JSONObject(it.readText())) }
            dir.deleteRecursively()
        }
        restart(app)
    }

    // ---------- ustawienia ----------

    private fun settingsJson(ctx: Context): JSONObject {
        val sp = ctx.getSharedPreferences("trailtrack", Context.MODE_PRIVATE)
        val out = JSONObject()
        for ((k, v) in sp.all) {
            if (k in SKIP_KEYS || v == null) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>()))
                else -> continue
            }
            out.put(k, o)
        }
        return out
    }

    private fun applySettings(ctx: Context, json: JSONObject) {
        val sp = ctx.getSharedPreferences("trailtrack", Context.MODE_PRIVATE)
        val ed = sp.edit().clear()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in SKIP_KEYS) continue
            val o = json.optJSONObject(k) ?: continue
            when (o.optString("t")) {
                "b" -> ed.putBoolean(k, o.optBoolean("v"))
                "i" -> ed.putInt(k, o.optInt("v"))
                "l" -> ed.putLong(k, o.optLong("v"))
                "f" -> ed.putFloat(k, o.optDouble("v").toFloat())
                "s" -> ed.putString(k, o.optString("v"))
                "ss" -> {
                    val a = o.optJSONArray("v") ?: JSONArray()
                    ed.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet())
                }
            }
        }
        ed.commit()
    }

    private fun appVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }.getOrDefault("?")

    /** Ponowne uruchomienie: nowy proces wczyta przywróconą bazę i ustawienia od zera. */
    private fun restart(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (i != null) ctx.startActivity(i)
        Process.killProcess(Process.myPid())
    }
}
