package com.example.jarvis.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.jarvis.core.device.MediaFile
import com.example.jarvis.core.device.MediaGateway
import com.example.jarvis.core.device.MediaResult
import com.example.jarvis.core.device.PixelDecoder
import com.example.jarvis.core.device.Pixels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Peticiones que atiende la actividad visible con ActivityResult (selector del sistema y cámara). */
sealed interface MediaRequest {
    class PickDocument(val mimeTypes: Array<String>, val result: CompletableDeferred<Uri?>) : MediaRequest
    class CapturePhoto(val target: Uri, val result: CompletableDeferred<Boolean>) : MediaRequest
}

data class RecordingState(val startedAt: Long, val maxSeconds: Int)

/**
 * Contenido que el usuario entrega a propósito: documento elegido en el selector del sistema (SAF),
 * foto con la app de cámara (sin permiso CAMERA: la toma la app del sistema) o nota de voz grabada
 * con un botón de detener visible. Nada se lee ni se graba en segundo plano.
 */
class MediaBroker(private val context: Context, private val permissions: PermissionBroker) : MediaGateway {
    private val _requests = MutableSharedFlow<MediaRequest>(extraBufferCapacity = 2)
    val requests: SharedFlow<MediaRequest> = _requests.asSharedFlow()

    private val _recording = MutableStateFlow<RecordingState?>(null)
    /** Grabación en curso: la interfaz muestra el tiempo y los botones Guardar/Cancelar. */
    val recording: StateFlow<RecordingState?> = _recording.asStateFlow()
    @Volatile private var stopSignal: CompletableDeferred<Boolean>? = null

    private val authority = "${context.packageName}.files"
    private fun visible() = permissions.activityAttached && ForegroundTracker.isForeground
    private fun captures() = File(context.cacheDir, "captures").apply { mkdirs() }

    override suspend fun pickDocument(mimeTypes: List<String>): MediaResult {
        if (!visible()) return MediaResult.Unavailable(NOT_VISIBLE)
        val d = CompletableDeferred<Uri?>()
        _requests.emit(MediaRequest.PickDocument(mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray(), d))
        val uri = withTimeoutOrNull(WAIT_MS) { d.await() } ?: return MediaResult.Cancelled
        return withContext(Dispatchers.IO) { readDocument(uri) }
    }

    private fun readDocument(uri: Uri): MediaResult {
        val resolver = context.contentResolver
        var name = "documento"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_DOCUMENT_BYTES) return MediaResult.Unavailable("El archivo pesa ${size / 1_048_576} MB; el máximo es ${MAX_DOCUMENT_BYTES / 1_048_576} MB.")
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_DOCUMENT_BYTES) return MediaResult.Unavailable("El archivo supera ${MAX_DOCUMENT_BYTES / 1_048_576} MB.")
            }
            out.toByteArray()
        } ?: return MediaResult.Unavailable("No se pudo abrir el archivo elegido.")
        return MediaResult.Ok(MediaFile(name, resolver.getType(uri) ?: "application/octet-stream", bytes))
    }

    override suspend fun capturePhoto(): MediaResult {
        if (!visible()) return MediaResult.Unavailable(NOT_VISIBLE)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return MediaResult.Unavailable("Este teléfono no tiene cámara.")
        val file = File(captures(), "foto-${System.currentTimeMillis()}.jpg")
        try {
            val d = CompletableDeferred<Boolean>()
            _requests.emit(MediaRequest.CapturePhoto(FileProvider.getUriForFile(context, authority, file), d))
            val taken = withTimeoutOrNull(WAIT_MS) { d.await() } ?: false
            if (!taken || !file.exists() || file.length() == 0L) return MediaResult.Cancelled
            val bytes = withContext(Dispatchers.Default) { ImageTools.shrinkJpeg(file.readBytes()) }
            return MediaResult.Ok(MediaFile("foto.jpg", "image/jpeg", bytes))
        } finally {
            file.delete()
        }
    }

    override suspend fun recordAudio(maxSeconds: Int): MediaResult {
        if (!visible()) return MediaResult.Unavailable(NOT_VISIBLE)
        if (_recording.value != null) return MediaResult.Unavailable("Ya hay una grabación en curso.")
        if (!withContext(Dispatchers.Main) { permissions.request(Manifest.permission.RECORD_AUDIO) }) {
            return MediaResult.Unavailable("Sin permiso de micrófono.")
        }
        val file = File(captures(), "nota-${System.currentTimeMillis()}.m4a")
        val stop = CompletableDeferred<Boolean>()
        var maxReached = false
        val recorder = withContext(Dispatchers.Main) {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(16_000)
                r.setAudioEncodingBitRate(48_000)
                r.setMaxDuration(maxSeconds * 1000)
                r.setOutputFile(file.absolutePath)
                r.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        maxReached = true
                        stop.complete(true)
                    }
                }
                r.prepare()
                r.start()
                r
            } catch (e: Exception) {
                r.release()
                null
            }
        }
        if (recorder == null) {
            file.delete()
            return MediaResult.Unavailable("No se pudo usar el micrófono (puede estar ocupado por la conversación de voz u otra app).")
        }
        stopSignal = stop
        _recording.value = RecordingState(System.currentTimeMillis(), maxSeconds)
        var keep = false
        try {
            keep = withTimeoutOrNull((maxSeconds + 5) * 1000L) { stop.await() } ?: true
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                _recording.value = null
                stopSignal = null
                val stopped = maxReached || runCatching { recorder.stop() }.isSuccess
                recorder.release()
                if (!keep || !stopped) file.delete()
            }
        }
        if (!keep) return MediaResult.Cancelled
        if (!file.exists() || file.length() == 0L) return MediaResult.Unavailable("La grabación quedó vacía (demasiado corta).")
        val bytes = withContext(Dispatchers.IO) { file.readBytes().also { file.delete() } }
        return MediaResult.Ok(MediaFile("nota.m4a", "audio/mp4", bytes))
    }

    /** Botones de la interfaz: true = guardar la grabación, false = descartarla. */
    fun finishRecording(keep: Boolean) {
        stopSignal?.complete(keep)
    }

    companion object {
        const val NOT_VISIBLE = "Abre JARVIS en pantalla para elegir un archivo, tomar una foto o grabar."
        const val MAX_DOCUMENT_BYTES = 10L * 1024 * 1024
        private const val WAIT_MS = 5 * 60_000L
    }
}

/** Utilidades de imagen con las API del sistema (sin dependencias). */
object ImageTools {
    /**
     * Reduce la foto a ≤ [maxSide] px por lado y la recomprime en JPEG, aplicando la rotación EXIF.
     * Basta para leer texto y queda muy por debajo del límite del servidor.
     */
    fun shrinkJpeg(bytes: ByteArray, maxSide: Int = 2048): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return bytes
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return bytes
        val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            val degrees = when (exifOrientation(bytes)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) postRotate(degrees)
        }
        val out = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val buffer = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 85, buffer)
        if (out !== decoded) out.recycle()
        decoded.recycle()
        return buffer.toByteArray()
    }

    private fun exifOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** Píxeles ARGB para leer códigos de barras/QR con ZXing (≤ 1600 px por lado). */
    val pixelDecoder = PixelDecoder { bytes ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@PixelDecoder null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@PixelDecoder null
        val argb = IntArray(bmp.width * bmp.height)
        bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        Pixels(bmp.width, bmp.height, argb).also { bmp.recycle() }
    }
}
