package pl.trailtrack

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Krótkie sygnały dźwiękowe generowane w kodzie (bez plików audio). */
enum class Beep(val label: String) {
    START("Start"), STOP("Koniec przejazdu"), PAUSE("Pauza"), RESUME("Wznowienie"), LAP("Okrążenie"),
    GOAL("Cel osiągnięty"), RECORD("Najlepszy wynik"), GOOD("Tempo lepsze"), BAD("Tempo słabsze"),
    EFFORT("Zwiększony wysiłek"), WARN("Ostrzeżenie"), ALARM("Alarm (GPS, zjazd z trasy)"), OK("Potwierdzenie")
}

/**
 * Wyjście dźwiękowe aplikacji: sygnały + synteza mowy (TextToSpeech wbudowany w Androida,
 * działa offline i bez Google Play Services). Muzyka z innych aplikacji jest na chwilę wyciszana.
 * Wszystkie metody można wołać z dowolnego wątku.
 */
object Sound {
    const val VOICE_UNKNOWN = 0
    const val VOICE_OK = 1
    const val VOICE_NO_POLISH = 2
    const val VOICE_FAILED = 3

    /** Stan syntezatora mowy (do podpowiedzi w ustawieniach). */
    val voiceState = MutableStateFlow(VOICE_UNKNOWN)

    private const val RATE = 22050

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = ArrayDeque<Pair<String, Boolean>>()
    private var counter = 0
    private var focus: AudioFocusRequest? = null
    private var busyUntil = 0L

    private val speechAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val beepAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val releaseRunnable = Runnable { releaseNow() }

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private fun volume(): Float = when (Prefs.cueVolume) {
        0 -> 0.35f
        1 -> 0.65f
        else -> 1.0f
    }

    // ---------------------------------------------------------------- API

    /**
     * Odtwarza sygnał [beep] i (po nim) wypowiada [text]. Respektuje główne przełączniki
     * „Dźwięki” / „Komunikaty głosowe”, chyba że [force] (podgląd w ustawieniach).
     * [urgent] = przerywa to, co aktualnie jest czytane.
     */
    fun cue(beep: Beep?, text: String? = null, urgent: Boolean = false, force: Boolean = false) {
        main.post {
            main.removeCallbacks(releaseRunnable)
            var delay = 0L
            if (beep != null && (force || Prefs.soundOn)) delay = playBeep(beep) + 60L
            if (text != null && (force || Prefs.voiceOn)) {
                if (delay > 0) main.postDelayed({ speakNow(text, urgent) }, delay) else speakNow(text, urgent)
            }
        }
    }

    /** Wstępnie uruchamia syntezator, żeby ustawienia mogły pokazać jego stan. */
    fun prepare() {
        main.post { ensureTts() }
    }

    /** Zwalnia syntezator po chwili bezczynności (po zakończeniu nagrywania). */
    fun scheduleRelease() {
        main.removeCallbacks(releaseRunnable)
        main.postDelayed(releaseRunnable, 20_000L)
    }

    // ---------------------------------------------------------------- mowa

    private fun ensureTts() {
        if (tts != null) return
        voiceState.value = VOICE_UNKNOWN
        tts = TextToSpeech(app) { status -> main.post { onTtsInit(status) } }
    }

    private fun onTtsInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            voiceState.value = VOICE_FAILED
            pending.clear()
            return
        }
        val r = t.setLanguage(Locale("pl", "PL"))
        voiceState.value =
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) VOICE_NO_POLISH else VOICE_OK
        t.setAudioAttributes(speechAttrs)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { afterSpeech() }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) { afterSpeech() }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { afterSpeech() }
        })
        ttsReady = true
        while (pending.isNotEmpty()) {
            val (text, urgent) = pending.removeFirst()
            speakNow(text, urgent)
        }
    }

    private fun afterSpeech() {
        main.postDelayed({
            val speaking = tts?.isSpeaking == true
            if (!speaking && System.currentTimeMillis() >= busyUntil) abandonFocus()
        }, 400L)
    }

    private fun speakNow(text: String, urgent: Boolean) {
        ensureTts()
        val t = tts
        if (t == null || !ttsReady) {
            // syntezator jeszcze się uruchamia – zachowaj komunikat (max kilka)
            if (voiceState.value != VOICE_FAILED) {
                pending.addLast(text to urgent)
                while (pending.size > 5) pending.removeFirst()
            }
            return
        }
        requestFocus()
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume()) }
        t.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "tt${counter++}")
    }

    private fun releaseNow() {
        abandonFocus()
        tts?.runCatching { stop(); shutdown() }
        tts = null
        ttsReady = false
        pending.clear()
        voiceState.value = VOICE_UNKNOWN
    }

    // ---------------------------------------------------------------- fokus audio

    private fun requestFocus() {
        if (focus != null) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttrs)
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(r)
        focus = r
    }

    private fun abandonFocus() {
        val r = focus ?: return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.abandonAudioFocusRequest(r)
        focus = null
    }

    // ---------------------------------------------------------------- sygnały

    /** (częstotliwość Hz, czas ms); 0 Hz = cisza */
    private fun pattern(b: Beep): List<Pair<Int, Int>> = when (b) {
        Beep.START -> listOf(660 to 120, 0 to 40, 880 to 180)
        Beep.STOP -> listOf(880 to 120, 0 to 40, 660 to 120, 0 to 40, 440 to 240)
        Beep.PAUSE -> listOf(600 to 110, 0 to 40, 450 to 170)
        Beep.RESUME -> listOf(450 to 110, 0 to 40, 600 to 170)
        Beep.LAP -> listOf(880 to 90, 0 to 60, 880 to 90)
        Beep.GOAL -> listOf(523 to 110, 659 to 110, 784 to 110, 1047 to 340)
        Beep.RECORD -> listOf(784 to 100, 988 to 100, 1175 to 100, 1568 to 100, 0 to 60, 1568 to 320)
        Beep.GOOD -> listOf(660 to 110, 880 to 190)
        Beep.BAD -> listOf(440 to 150, 330 to 240)
        Beep.EFFORT -> listOf(988 to 90, 0 to 70, 988 to 90, 0 to 70, 988 to 90)
        Beep.WARN -> listOf(440 to 180, 0 to 90, 440 to 180)
        Beep.ALARM -> listOf(880 to 160, 660 to 160, 880 to 160, 660 to 160, 880 to 160, 660 to 160)
        Beep.OK -> listOf(660 to 100, 0 to 30, 990 to 140)
    }

    /** Zwraca czas trwania sygnału w ms. */
    private fun playBeep(b: Beep): Long {
        val tones = pattern(b)
        val amp = volume() * 0.85
        val total = tones.sumOf { RATE * it.second / 1000 }
        val pcm = ShortArray(total)
        var pos = 0
        val fade = (RATE * 0.008).toInt().coerceAtLeast(1)
        for ((hz, ms) in tones) {
            val n = RATE * ms / 1000
            if (hz > 0) {
                for (k in 0 until n) {
                    val env = min(1.0, min(k.toDouble() / fade, (n - k).toDouble() / fade))
                    pcm[pos + k] = (sin(2.0 * PI * hz * k / RATE) * env * amp * Short.MAX_VALUE).toInt().toShort()
                }
            }
            pos += n
        }
        val durMs = total * 1000L / RATE
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(beepAttrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(total * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, total)
            requestFocus()
            busyUntil = System.currentTimeMillis() + durMs + 200
            track.play()
            main.postDelayed({
                runCatching { track.release() }
                afterSpeech()
            }, durMs + 150)
        }
        return durMs
    }
}
