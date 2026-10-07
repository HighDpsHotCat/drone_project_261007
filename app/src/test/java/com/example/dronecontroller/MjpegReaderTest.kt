package com.example.dronecontroller

import com.example.dronecontroller.camera.MjpegReader
import com.example.dronecontroller.camera.streamEndpoint
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class MjpegReaderTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 12, 34, 0xff.toByte(), 0, 56, 0xff.toByte(), 0xd9.toByte())
    @Test fun multipartHeadersAndSuccessiveFramesAreParsed() {
        val header = "\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 9\r\n\r\n".toByteArray()
        val reader = MjpegReader(ByteArrayInputStream(header + jpeg + header + jpeg))
        assertArrayEquals(jpeg, reader.nextFrame())
        assertArrayEquals(jpeg, reader.nextFrame())
    }
    @Test(expected = EOFException::class) fun truncatedFrameDoesNotReturnPartialImage() {
        MjpegReader(ByteArrayInputStream(jpeg.dropLast(1).toByteArray())).nextFrame()
    }
    @Test(expected = IllegalStateException::class) fun oversizedFrameIsRejected() {
        MjpegReader(ByteArrayInputStream(jpeg), 6).nextFrame()
    }
    @Test(expected = IllegalStateException::class) fun endlessNonImageInputIsBounded() {
        MjpegReader(ByteArrayInputStream(ByteArray(20)), 10).nextFrame()
    }
    @Test fun privateWifiAddressesAreAccepted() {
        for (address in listOf("192.168.4.1", "10.0.0.4", "172.16.0.2", "169.254.1.1"))
            assertEquals(address, streamEndpoint("http://$address:81/stream").host)
    }
    @Test fun externalAddressesAndCredentialUrlsAreRejected() {
        for (address in listOf("http://example.com/stream", "http://8.8.8.8/stream", "file:///etc/passwd",
            "http://user:password@192.168.4.1/stream", "http://192.168.4.1:70000/stream", "http://127.0.0.1/stream")) {
            try { streamEndpoint(address); fail("Accepted $address") } catch (_: IllegalArgumentException) { }
        }
    }
}
