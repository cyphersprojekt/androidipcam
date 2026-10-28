package com.example.camaraip

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Codifica cuadros YUV de CameraX a H.264 con el encoder de hardware del teléfono (MediaCodec).
 * Entrega SPS/PPS por [onConfig] y cada cuadro codificado, separado en NAL units, por [onFrame].
 *
 * [encode] y [stop] se llaman desde el hilo de la cámara; la salida se lee en un hilo propio.
 */
class H264Encoder(
    val width: Int,
    val height: Int,
    fps: Int,
    bitrate: Int,
    private val onConfig: (sps: ByteArray, pps: ByteArray) -> Unit,
    private val onFrame: (nals: List<ByteArray>, ptsUs: Long, keyFrame: Boolean) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val rowBuffer = ByteArray(width)
    @Volatile private var running = true
    private val drainThread: Thread

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_S)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        drainThread = thread(name = "h264-encoder") { drain() }
    }

    /** Si el encoder todavía está ocupado con cuadros anteriores, este se descarta. */
    fun encode(image: ImageProxy) {
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return
        val ptsUs = image.imageInfo.timestamp / 1000
        val input = codec.getInputImage(index)
        if (input == null) {
            codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
            return
        }
        copyYuv(image, input)
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
    }

    /** Pide un keyframe ya mismo (cuando se conecta un cliente nuevo). Se puede llamar desde cualquier hilo. */
    fun requestKeyFrame() {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            // El encoder se está deteniendo.
        }
    }

    fun stop() {
        running = false
        drainThread.join(1000)
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            // Ya estaba detenido.
        }
        codec.release()
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val index = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    val csd = listOfNotNull(format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1"))
                    publishConfig(csd.flatMap { H264.splitAnnexB(it.toByteArray()) })
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val nals = H264.splitAnnexB(buffer.toByteArray())
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            publishConfig(nals)
                        } else {
                            onFrame(nals, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            } catch (e: IllegalStateException) {
                break // encoder detenido
            } catch (e: Exception) {
                Log.w(TAG, "Error leyendo la salida del encoder", e)
            }
        }
    }

    private fun publishConfig(nals: List<ByteArray>) {
        val sps = nals.firstOrNull { H264.nalType(it) == H264.NAL_SPS } ?: return
        val pps = nals.firstOrNull { H264.nalType(it) == H264.NAL_PPS } ?: return
        onConfig(sps, pps)
    }

    /** Copia plano por plano respetando el stride de cada lado (cámara y encoder no siempre coinciden). */
    private fun copyYuv(src: ImageProxy, dst: Image) {
        for (plane in 0..2) {
            val w = if (plane == 0) width else width / 2
            val h = if (plane == 0) height else height / 2
            val s = src.planes[plane]
            val d = dst.planes[plane]
            copyPlane(s.buffer, s.rowStride, s.pixelStride, d.buffer, d.rowStride, d.pixelStride, w, h)
        }
    }

    private fun copyPlane(
        src: ByteBuffer, srcRowStride: Int, srcPixelStride: Int,
        dst: ByteBuffer, dstRowStride: Int, dstPixelStride: Int,
        w: Int, h: Int,
    ) {
        if (srcPixelStride == 1 && dstPixelStride == 1) {
            for (y in 0 until h) {
                src.position(y * srcRowStride)
                src.get(rowBuffer, 0, w)
                dst.position(y * dstRowStride)
                dst.put(rowBuffer, 0, w)
            }
        } else {
            for (y in 0 until h) {
                val srcRow = y * srcRowStride
                val dstRow = y * dstRowStride
                for (x in 0 until w) dst.put(dstRow + x * dstPixelStride, src.get(srcRow + x * srcPixelStride))
            }
        }
    }

    private fun ByteBuffer.toByteArray() = ByteArray(remaining()).also { duplicate().get(it) }

    private companion object {
        const val TAG = "H264Encoder"
        const val KEY_FRAME_INTERVAL_S = 1
        const val DRAIN_TIMEOUT_US = 10_000L
    }
}
