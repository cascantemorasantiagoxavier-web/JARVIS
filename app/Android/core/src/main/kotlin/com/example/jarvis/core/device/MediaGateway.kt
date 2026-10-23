package com.example.jarvis.core.device

/** Archivo, foto o audio que el usuario entrega explícitamente (selector, cámara, grabadora). */
class MediaFile(val name: String, val mimeType: String, val bytes: ByteArray)

sealed interface MediaResult {
    data class Ok(val file: MediaFile) : MediaResult
    data object Cancelled : MediaResult
    data class Unavailable(val reason: String) : MediaResult
}

/**
 * Obtención de contenido con intervención del usuario: nada se lee ni se graba sin que él lo elija
 * o pulse el botón. En Android lo atiende la actividad visible (Storage Access Framework, cámara,
 * grabadora); sin actividad visible devuelve Unavailable.
 */
interface MediaGateway {
    suspend fun pickDocument(mimeTypes: List<String>): MediaResult
    suspend fun capturePhoto(): MediaResult
    suspend fun recordAudio(maxSeconds: Int): MediaResult
}

/** Para entornos sin interfaz (pruebas, escritorio): todo devuelve Unavailable. */
object NoMedia : MediaGateway {
    override suspend fun pickDocument(mimeTypes: List<String>) = MediaResult.Unavailable("No hay selector de archivos en este entorno.")
    override suspend fun capturePhoto() = MediaResult.Unavailable("No hay cámara en este entorno.")
    override suspend fun recordAudio(maxSeconds: Int) = MediaResult.Unavailable("No hay grabadora en este entorno.")
}

/** Píxeles ARGB de una imagen. */
class Pixels(val width: Int, val height: Int, val argb: IntArray)

/** Decodifica JPEG/PNG a píxeles. En Android lo implementa BitmapFactory; sin implementación devuelve null. */
fun interface PixelDecoder {
    fun decode(bytes: ByteArray): Pixels?
}
