package de.smartzone.pocketclaude.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Komprimiert Bilder vor dem Upload — analog zu dem, was ChatGPT, Claude
 * und Gemini auf ihrer Client-Seite machen.
 *
 * Strategie:
 *  - Max. längste Kante 1568 px (Anthropic-Empfehlung für Vision-Eingabe).
 *  - JPEG mit Quality 85 (visuell verlustfrei für Fotos, drastisch kleiner).
 *  - Skipped wenn Datei bereits unter dem Schwellwert ist UND klein genug.
 *  - EXIF-Orientation wird vor dem Encode angewandt — sonst landet das Bild
 *    seitlich, wenn das Telefon im Portrait-Modus geschossen hat.
 *
 * Nicht-Bilder (PDF, txt, …) werden unverändert durchgereicht.
 */
object ImageCompressor {

    private const val MAX_EDGE_PX = 1568
    private const val JPEG_QUALITY = 85
    private const val SKIP_IF_BELOW_BYTES = 200 * 1024  // unter 200 KB lohnt nichts

    data class Result(val filename: String, val mime: String, val bytes: ByteArray)

    operator fun Result.component1() = filename
    operator fun Result.component2() = mime
    operator fun Result.component3() = bytes

    /** Hauptaufruf. Bei Nicht-Bild oder schon-klein-genug: 1:1 zurück. */
    fun maybeCompress(filename: String, mime: String, bytes: ByteArray): Result {
        val isImage = mime.startsWith("image/")
        if (!isImage) return Result(filename, mime, bytes)
        // Animated GIFs/WebP würden durch das Recodieren ihre Animation verlieren.
        if (mime == "image/gif") return Result(filename, mime, bytes)
        if (bytes.size <= SKIP_IF_BELOW_BYTES) return Result(filename, mime, bytes)

        return try {
            compress(filename, bytes) ?: Result(filename, mime, bytes)
        } catch (_: Throwable) {
            // Bei jedem Fehler: Original durchreichen, lieber großer Upload als gar keiner.
            Result(filename, mime, bytes)
        }
    }

    private fun compress(filename: String, bytes: ByteArray): Result? {
        // 1) Größen rausfinden ohne den ganzen Bitmap zu laden.
        val sizeOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sizeOpts)
        val srcW = sizeOpts.outWidth
        val srcH = sizeOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        val longest = maxOf(srcW, srcH)
        if (longest <= MAX_EDGE_PX && bytes.size <= 1_500_000) {
            // Schon klein genug — Original (mit originalem MIME) behalten.
            return Result(filename, sizeOpts.outMimeType ?: "image/jpeg", bytes)
        }

        // 2) inSampleSize berechnen (Power of 2). Beispiel: 4032 → 1568 → factor ~2.57,
        //    wir nehmen 2 (= 2016px Edge), und resizen danach präzise mit Matrix.
        var inSample = 1
        while (longest / (inSample * 2) >= MAX_EDGE_PX) inSample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = inSample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return null

        // 3) Auf MAX_EDGE_PX skalieren, EXIF-Rotation berücksichtigen.
        // WICHTIG: jedes Zwischen-Bitmap recyceln, sobald wir sein Output haben —
        // sonst Memory-Leak bei großen Eingaben (Original-Foto kann 4 MB sein,
        // bleibt sonst zusammen mit der gedrehten + skalierten Variante im Heap).
        val rotation = readExifRotation(bytes)
        val rotatedFirst = applyRotation(decoded, rotation)
        if (rotatedFirst !== decoded) decoded.recycle()

        val w = rotatedFirst.width
        val h = rotatedFirst.height
        val scale = MAX_EDGE_PX.toFloat() / maxOf(w, h).toFloat()
        val scaled = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(
                rotatedFirst,
                (w * scale).toInt(), (h * scale).toInt(),
                /* filter = */ true,
            )
        } else {
            rotatedFirst
        }
        if (scaled !== rotatedFirst) rotatedFirst.recycle()

        // 4) JPEG encoden
        val out = ByteArrayOutputStream(64 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()

        val newName = renameToJpg(filename)
        return Result(newName, "image/jpeg", out.toByteArray())
    }

    private fun readExifRotation(bytes: ByteArray): Int = try {
        ByteArrayInputStream(bytes).use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    } catch (_: Throwable) { 0 }

    private fun applyRotation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun renameToJpg(name: String): String {
        val base = name.substringBeforeLast('.', name)
        return "$base.jpg"
    }
}
