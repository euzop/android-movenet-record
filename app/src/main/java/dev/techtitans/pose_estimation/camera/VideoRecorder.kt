package dev.techtitans.pose_estimation.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Utility for recording video with real-time pose overlay
 */
class VideoRecorder(
    val width: Int,
    val height: Int,
    private val frameRate: Int,
    private val outputFile: File,
    private val onError: (Exception) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var trackIndex = -1
    private var isRecording = AtomicBoolean(false)
    private var recordingSurface: Surface? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private val handler = Handler(Looper.getMainLooper())
    private var frameCount = 0
    private var startTime = 0L

    /**
     * Initialize the encoder and prepare for recording
     */
    fun prepare() {
        try {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, width, height)
            val bitRate = width * height * 4 // Adjust as needed for quality/size balance
            
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe every second
            
            mediaCodec = MediaCodec.createEncoderByType(mime)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            recordingSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()
            
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            trackIndex = -1
            
            isRecording.set(true)
            
            // Start draining the encoder in a separate thread
            Thread { drainEncoder() }.start()
            
        } catch (e: Exception) {
            onError(e)
        }
    }

    /**
     * Draw a frame to the recording surface
     */
    fun drawFrame(bitmap: Bitmap) {
        if (!isRecording.get() || recordingSurface == null) return
        
        try {
            val canvas = recordingSurface?.lockCanvas(null)
            canvas?.let {
                // Calculate scaling to maintain aspect ratio
                val srcWidth = bitmap.width
                val srcHeight = bitmap.height
                val srcRatio = srcWidth.toFloat() / srcHeight
                val dstRatio = width.toFloat() / height
                
                // Draw black background first
                it.drawColor(Color.BLACK)
                
                // Calculate target dimensions to preserve aspect ratio
                val dstRect: RectF
                
                if (srcRatio > dstRatio) {
                    // Source is wider than destination - fit width
                    val scaledHeight = width / srcRatio
                    val yOffset = (height - scaledHeight) / 2f
                    dstRect = RectF(0f, yOffset, width.toFloat(), yOffset + scaledHeight)
                } else {
                    // Source is taller than destination - fit height
                    val scaledWidth = height * srcRatio
                    val xOffset = (width - scaledWidth) / 2f
                    dstRect = RectF(xOffset, 0f, xOffset + scaledWidth, height.toFloat())
                }
                
                // Draw the bitmap scaled to fit while preserving aspect ratio
                it.drawBitmap(
                    bitmap,
                    Rect(0, 0, srcWidth, srcHeight),
                    dstRect,
                    null
                )
                recordingSurface?.unlockCanvasAndPost(it)
            }
        } catch (e: Exception) {
            Log.e("VideoRecorder", "Error drawing frame: ${e.message}")
        }
    }

    /**
     * Stop recording and clean up resources
     */
    fun stop() {
        isRecording.set(false)
        
        try {
            // Give time for final frames to be encoded
            Thread.sleep(500)
            
            mediaCodec?.stop()
            mediaCodec?.release()
            
            recordingSurface?.release()
            
            if (trackIndex != -1) {
                mediaMuxer?.stop()
                mediaMuxer?.release()
            }
        } catch (e: Exception) {
            Log.e("VideoRecorder", "Error stopping recording: ${e.message}")
        } finally {
            mediaCodec = null
            recordingSurface = null
            mediaMuxer = null
            trackIndex = -1
        }
    }

    /**
     * Continuously drain the encoder to write data to the output file
     */
    private fun drainEncoder() {
        while (isRecording.get()) {
            try {
                val bufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US) ?: -1
                
                if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output available yet
                    try { Thread.sleep(10) } catch (e: InterruptedException) { }
                    continue
                } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Encoder format changed, must set up muxer
                    if (trackIndex == -1) {
                        val format = mediaCodec?.outputFormat
                        trackIndex = format?.let { mediaMuxer?.addTrack(it) } ?: -1
                        mediaMuxer?.start()
                        startTime = bufferInfo.presentationTimeUs
                    }
                } else if (bufferIndex >= 0) {
                    // Got a buffer
                    val encodedData = mediaCodec?.getOutputBuffer(bufferIndex)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Ignore codec config data
                        bufferInfo.size = 0
                    }
                    
                    if (bufferInfo.size > 0 && trackIndex != -1) {
                        encodedData?.position(bufferInfo.offset)
                        encodedData?.limit(bufferInfo.offset + bufferInfo.size)
                        
                        // Adjust presentation time to make it start from 0
                        bufferInfo.presentationTimeUs -= startTime
                        
                        mediaMuxer?.writeSampleData(trackIndex, encodedData!!, bufferInfo)
                        
                        frameCount++
                    }
                    
                    mediaCodec?.releaseOutputBuffer(bufferIndex, false)
                }
                
            } catch (e: Exception) {
                Log.e("VideoRecorder", "Exception in drainEncoder: ${e.message}")
                handler.post { onError(e) }
                isRecording.set(false)
            }
        }
    }

    companion object {
        private const val TIMEOUT_US = 10000L
    }
}