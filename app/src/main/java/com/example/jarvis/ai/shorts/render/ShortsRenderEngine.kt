package com.example.jarvis.ai.shorts.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.jarvis.domain.shorts.ShortProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ShortsRenderEngine {

    suspend fun renderShortVideo(
        context: Context,
        project: ShortProject
    ): Result<File> = withContext(Dispatchers.IO) {
        val width = 1080
        val height = 1920
        val frameRate = 30
        val durationSec = project.targetDurationSeconds.coerceAtLeast(5)
        val totalFrames = durationSec * frameRate

        val outputFile = File(context.filesDir, "short_${project.id}.mp4")
        if (outputFile.exists()) outputFile.delete()

        try {
            Log.i("JARVIS", "[SHORTS_RENDER] Starting MP4 render 1080x1920 for project: ${project.id}")

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 52f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }

            val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.CYAN
                textSize = 36f
                typeface = Typeface.MONOSPACE
                textAlign = Paint.Align.CENTER
            }

            val frameDurationUs = 1000000L / frameRate

            for (i in 0 until totalFrames) {
                val canvas: Canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawColor(Color.parseColor("#0A0E1A"))

                    // HUD Header
                    canvas.drawText("J.A.R.V.I.S. SHORTS AUTOMATION", (width / 2).toFloat(), 200f, hudPaint)
                    canvas.drawText("STARK TECH CREATOR ENGINE", (width / 2).toFloat(), 260f, hudPaint)

                    // Topic Title
                    canvas.drawText(project.topic.uppercase(), (width / 2).toFloat(), 600f, paint)

                    // Script Lines (Hook / Body / CTA)
                    val scriptText = project.script?.hook ?: "¡Increíble dato sobre la tecnología!"
                    canvas.drawText(scriptText, (width / 2).toFloat(), 1000f, paint)

                    // Animated Progress Bar
                    val progress = i.toFloat() / totalFrames
                    val barWidth = width * 0.8f
                    val left = (width - barWidth) / 2
                    val top = 1600f
                    val right = left + (barWidth * progress)
                    val bottom = 1620f

                    val barBgPaint = Paint().apply { color = Color.DKGRAY }
                    val barFgPaint = Paint().apply { color = Color.CYAN }
                    canvas.drawRect(left, top, left + barWidth, bottom, barBgPaint)
                    canvas.drawRect(left, top, right, bottom, barFgPaint)

                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }

                while (true) {
                    val status = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (status >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            bufferInfo.presentationTimeUs = i * frameDurationUs
                            val buffer = encoder.getOutputBuffer(status)
                            if (buffer != null) {
                                muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(status, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    } else {
                        break
                    }
                }
            }

            encoder.signalEndOfInputStream()
            while (true) {
                val status = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (status >= 0) {
                    if (bufferInfo.size > 0 && muxerStarted) {
                        val buffer = encoder.getOutputBuffer(status)
                        if (buffer != null) {
                            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(status, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                } else if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                }
            }

            encoder.stop()
            encoder.release()
            if (muxerStarted) {
                muxer.stop()
                muxer.release()
            }

            Log.i("JARVIS", "[SHORTS_RENDER] MP4 render completed successfully: ${outputFile.absolutePath} size=${outputFile.length()} bytes")
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e("JARVIS", "[SHORTS_RENDER] Failed MP4 render: ${e.message}", e)
            Result.failure(e)
        }
    }
}
