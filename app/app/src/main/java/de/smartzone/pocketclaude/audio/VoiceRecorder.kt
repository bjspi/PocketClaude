package de.smartzone.pocketclaude.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kapselt eine MediaRecorder-Session für Voice-Input.
 *
 * Audio-Format: MPEG-4-Container + AAC, 16 kHz, mono — passt 1:1 zu dem
 * Format, das das `AI-Voice-Keyboard`-Projekt an Groq Whisper schickt,
 * dort gut getestet. Datei landet im internen Cache-Ordner (`/voice-XXXXX.m4a`)
 * und wird nach dem Hochladen entsorgt.
 *
 * Lifecycle:
 *   - `start()` legt Datei an, startet MediaRecorder
 *   - `stop()` schließt sauber ab, liefert das File-Handle für den Upload
 *   - `cancel()` bricht ab und löscht die Datei
 *
 * Thread-Safety: start/stop/cancel sind durch eine Atomic-Flag geschützt;
 * mehrfaches stop() ist idempotent.
 */
class VoiceRecorder(private val appContext: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val active = AtomicBoolean(false)
    private var startedAtMs: Long = 0L

    val isRecording: Boolean get() = active.get()
    /** Dauer der aktuellen Session in ms (0 wenn nicht aktiv). */
    val elapsedMs: Long get() = if (active.get() && startedAtMs > 0) {
        System.currentTimeMillis() - startedAtMs
    } else 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /** Startet die Aufnahme. Wirft `SecurityException` ohne Mikro-Permission;
     *  eine `IllegalStateException` nur wenn der MediaRecorder selbst nicht
     *  starten kann (Mikro belegt von einer anderen App o.ä.).
     *
     *  Wenn der Recorder noch eine Leftover-Session aus einer vorigen VM
     *  oder einem schiefgegangenen Cleanup hält (z.B. beim Chat-Wechsel),
     *  wird die VORHER sauber abgebrochen — sonst würden alle weiteren
     *  Recordings für immer fehlschlagen, weil der Singleton-Recorder
     *  als "active" gilt. */
    @Throws(IllegalStateException::class, SecurityException::class)
    fun start() {
        if (active.get()) {
            Log.w(TAG, "Leftover recording detected on start() — cancelling first.")
            cancel()
        }
        if (!hasPermission()) {
            throw SecurityException("RECORD_AUDIO permission missing.")
        }
        val cacheDir = File(appContext.cacheDir, "voice").apply { mkdirs() }
        val file = File(cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            // VOICE_RECOGNITION matches what AI-Voice-Keyboard uses by default —
            // Android wendet keine Anruf-spezifische Echo-/AGC-Postprocessing an,
            // sondern liefert das rohe Mikro-Signal mit leichter Noise-Suppression.
            rec.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(48_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (t: Throwable) {
            runCatching { rec.release() }
            file.delete()
            throw IllegalStateException("MediaRecorder konnte nicht starten: ${t.message}", t)
        }
        recorder = rec
        outputFile = file
        startedAtMs = System.currentTimeMillis()
        active.set(true)
        Log.d(TAG, "Recording started → ${file.absolutePath}")
    }

    /** Stoppt die Aufnahme und gibt das fertige File zurück.
     *  Wenn keine Aufnahme aktiv ist oder die Aufnahme zu kurz war (<200 ms),
     *  liefert es `null`. Datei muss vom Caller gelöscht werden. */
    fun stop(): File? {
        if (!active.compareAndSet(true, false)) return null
        val rec = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        val durMs = if (startedAtMs > 0) System.currentTimeMillis() - startedAtMs else 0L
        startedAtMs = 0L
        if (rec == null || file == null) return null
        try {
            rec.stop()
        } catch (t: Throwable) {
            // `stop()` knallt z.B. wenn der Recorder weniger als 1 valide
            // Frame aufgenommen hat. Datei ggf. trotzdem da, aber unbrauchbar.
            Log.w(TAG, "MediaRecorder.stop() failed: $t")
            runCatching { rec.release() }
            file.delete()
            return null
        }
        runCatching { rec.release() }
        if (durMs < 200) {
            Log.d(TAG, "Recording too short ($durMs ms) — discarded.")
            file.delete()
            return null
        }
        Log.d(TAG, "Recording stopped — ${file.length()} bytes, ${durMs} ms")
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    /** Bricht die Aufnahme ab, löscht die Datei. */
    fun cancel() {
        if (!active.compareAndSet(true, false)) return
        val rec = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        startedAtMs = 0L
        try { rec?.stop() } catch (_: Throwable) {}
        runCatching { rec?.release() }
        file?.delete()
        Log.d(TAG, "Recording cancelled.")
    }

    /** Falls die App im Hintergrund gekillt wird, aufräumen. */
    fun release() {
        cancel()
    }

    companion object {
        private const val TAG = "VoiceRecorder"
    }
}
