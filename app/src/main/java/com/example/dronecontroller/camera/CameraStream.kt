package com.example.dronecontroller.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CameraStream(context: Context, private val onFrame: (Bitmap) -> Unit,
                   private val onStatus: (String, Boolean) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var active: Session? = null
    private class Session {
        val cancelled = AtomicBoolean(false)
        val latest = AtomicReference<Bitmap?>(null)
        val deliveryQueued = AtomicBoolean(false)
        @Volatile var connection: HttpURLConnection? = null
    }

    fun start(address: String) {
        stop()
        val endpoint = try { streamEndpoint(address) } catch (e: Exception) {
            onStatus(e.message ?: "영상 주소를 확인하세요.", false); return
        }
        val wifi = connectivity.allNetworks.firstOrNull {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (wifi == null) { onStatus("휴대폰을 기체 Wi-Fi에 연결한 후 영상을 다시 연결하세요.", false); return }
        val session = Session(); active = session
        onStatus("카메라 연결 중…", false)
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                if (session.cancelled.get()) return@execute
                connection = wifi.openConnection(endpoint.toURL()) as HttpURLConnection
                session.connection = connection
                connection.connectTimeout = 4000; connection.readTimeout = 4000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "multipart/x-mixed-replace,image/jpeg")
                require(connection.responseCode == 200) { "카메라 HTTP 오류 ${connection.responseCode}" }
                val contentType = connection.contentType.orEmpty().lowercase()
                require(contentType.startsWith("multipart/x-mixed-replace") || contentType.startsWith("image/jpeg")) {
                    "MJPEG 영상 주소가 아닙니다. /stream 주소를 확인하세요."
                }
                connection.inputStream.buffered(64 * 1024).use { input ->
                    val reader = MjpegReader(input)
                    var lastDecode = 0L
                    while (!session.cancelled.get()) {
                        val jpeg = reader.nextFrame()
                        val now = System.nanoTime()
                        if (now - lastDecode < 50_000_000) continue
                        lastDecode = now
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "지원하지 않는 영상 크기입니다." }
                        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                        while (bounds.outWidth / options.inSampleSize > 1280 || bounds.outHeight / options.inSampleSize > 960)
                            options.inSampleSize *= 2
                        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
                            ?: throw IllegalStateException("JPEG 영상을 해석하지 못했습니다.")
                        session.latest.set(bitmap)
                        if (session.deliveryQueued.compareAndSet(false, true)) main.post {
                            session.deliveryQueued.set(false)
                            if (active === session && !session.cancelled.get()) {
                                session.latest.getAndSet(null)?.let { onFrame(it) }
                                onStatus("영상 수신 중", true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                main.post { if (active === session && !session.cancelled.get())
                    onStatus("영상 연결 실패: ${e.message ?: "네트워크를 확인하세요."}", false) }
            } finally { connection?.disconnect() }
        }
    }

    fun stop() {
        val previous = active; active = null
        previous?.cancelled?.set(true); previous?.latest?.set(null)
        // HttpURLConnection.disconnect may block; never run it on the UI thread.
        if (previous != null) Thread { previous.connection?.disconnect() }.start()
    }
    fun close() { stop(); executor.shutdownNow() }
}
