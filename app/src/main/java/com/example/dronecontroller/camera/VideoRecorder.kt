package com.example.dronecontroller.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The encoder and muxer are confined to one worker. At most one frame is queued. */
class VideoRecorder(private val context: Context, firstFrame: Bitmap,
                    private val onStarted: () -> Unit, private val onFinished: (Uri?, String) -> Unit) {
    private val latest = AtomicReference<Bitmap?>(firstFrame)
    private val stopping = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val width = 640
    private val height = 480
    private val worker = Thread({ encode() }, "drone-mp4-recorder")
    fun start() { worker.start() }
    fun offer(frame: Bitmap) { if (!stopping.get()) latest.set(frame) }
    fun stop() { stopping.set(true) }

    private fun encode() {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var output: Uri? = null
        var muxerStarted = false
        var track = -1
        var samples = 0
        var success = false
        var failure = ""
        val start = System.nanoTime()
        var lastPts = -1L
        try {
            val fileName = "Drone_${System.currentTimeMillis()}.mp4"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneController")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                output = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("영상 저장 위치를 만들지 못했습니다.")
                context.contentResolver.openFileDescriptor(output, "rw")!!.use {
                    muxer = MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }
            } else {
                val folder = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: error("저장 공간을 사용할 수 없습니다.")
                folder.mkdirs()
                val file = File(folder, fileName)
                output = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            val encoder = MediaCodec.createEncoderByType("video/avc"); codec = encoder
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            main.post { if (!stopping.get()) onStarted() }
            val info = MediaCodec.BufferInfo()
            val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val pixels = IntArray(width * height)
            var eosQueued = false
            var eosSeen = false
            var endDeadline = Long.MAX_VALUE
            while (!eosSeen) {
                if (stopping.get() && endDeadline == Long.MAX_VALUE) endDeadline = System.nanoTime() + 3_000_000_000L
                if (System.nanoTime() > endDeadline) error("녹화 종료 시간이 초과되었습니다.")
                if (!eosQueued) {
                    val frame = if (stopping.get()) null else latest.getAndSet(null)
                    if (frame != null || stopping.get()) {
                        val index = encoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val pts = ((System.nanoTime() - start) / 1000).coerceAtLeast(lastPts + 1)
                            lastPts = pts
                            if (stopping.get()) {
                                encoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
                            } else if (frame != null) {
                                canvas.drawColor(Color.BLACK)
                                val ratio = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
                                val w = (frame.width * ratio).toInt(); val h = (frame.height * ratio).toInt()
                                canvas.drawBitmap(frame, null, Rect((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2), paint)
                                canvasBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                                val image = encoder.getInputImage(index) ?: error("이 휴대폰의 영상 인코더가 YUV 입력을 지원하지 않습니다.")
                                image.use {
                                    val planes = image.planes
                                    val plane = planes[0]; val up = planes[1]; val vp = planes[2]
                                    for (y in 0 until height) for (x in 0 until width) {
                                        val rgb = pixels[y * width + x]
                                        val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
                                        val yp = ((66*r + 129*g + 25*b + 128) shr 8) + 16
                                        plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, yp.coerceIn(0,255).toByte())
                                        if (x % 2 == 0 && y % 2 == 0) {
                                            val u = (((-38*r - 74*g + 112*b + 128) shr 8) + 128).coerceIn(0,255).toByte()
                                            val v = (((112*r - 94*g - 18*b + 128) shr 8) + 128).coerceIn(0,255).toByte()
                                            up.buffer.put(y/2 * up.rowStride + x/2 * up.pixelStride, u)
                                            vp.buffer.put(y/2 * vp.rowStride + x/2 * vp.pixelStride, v)
                                        }
                                    }
                                }
                                encoder.queueInputBuffer(index, 0, width*height*3/2, pts, 0)
                            }
                        } else if (frame != null) latest.compareAndSet(null, frame)
                    }
                }
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted)
                        track = muxer!!.addTrack(encoder.outputFormat); muxer!!.start(); muxerStarted = true
                    } else if (index >= 0) {
                        val buffer = encoder.getOutputBuffer(index) ?: error("인코더 출력 오류")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(muxerStarted)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(track, buffer, info); samples++
                        }
                        eosSeen = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(index, false)
                        if (eosSeen) break
                    } else break
                }
                if (!stopping.get()) Thread.sleep(90)
            }
            canvasBitmap.recycle()
            if (!muxerStarted || samples == 0) error("녹화할 영상 프레임이 없습니다.")
            muxer!!.stop(); muxerStarted = false
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(output!!,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            success = true
        } catch (e: Exception) { failure = e.message ?: "영상 인코더 오류" }
        finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            if (!success && output != null) try { context.contentResolver.delete(output, null, null) } catch (_: Exception) { }
            latest.set(null)
            val uri = if (success) output else null
            val description = if (success) {
                if (Build.VERSION.SDK_INT >= 29) "MP4 저장 완료 · 갤러리의 DroneController 폴더" else "MP4 저장 완료 · 공유 버튼으로 내보내세요."
            } else "녹화 실패: $failure"
            main.post { onFinished(uri, description) }
        }
    }
}
