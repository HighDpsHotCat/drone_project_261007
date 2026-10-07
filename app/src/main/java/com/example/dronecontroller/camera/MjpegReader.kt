package com.example.dronecontroller.camera

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.URI

/** Bounded JPEG marker parser; multipart headers may be split across any network read. */
class MjpegReader(private val input: InputStream, private val maxFrameBytes: Int = 2 * 1024 * 1024) {
    fun nextFrame(): ByteArray {
        var previous = -1
        var searched = 0
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException("카메라 스트림이 종료되었습니다.")
            if (previous == 0xff && value == 0xd8) break
            previous = value
            if (++searched > maxFrameBytes) throw IllegalStateException("JPEG 영상 데이터를 찾지 못했습니다.")
        }
        val out = ByteArrayOutputStream(64 * 1024)
        out.write(0xff); out.write(0xd8); previous = 0xd8
        while (out.size() < maxFrameBytes) {
            val value = input.read()
            if (value < 0) throw EOFException("카메라 프레임이 중간에 끊겼습니다.")
            out.write(value)
            if (previous == 0xff && value == 0xd9) return out.toByteArray()
            previous = value
        }
        throw IllegalStateException("카메라 프레임이 너무 큽니다. 해상도를 낮춰 주세요.")
    }
}

/** Local IPv4 endpoints only: no redirects or public cleartext hosts. */
fun streamEndpoint(value: String): URI {
    val uri = URI(value.trim())
    require(uri.scheme in listOf("http", "https") && uri.userInfo == null && uri.fragment == null) {
        "http 또는 https 영상 주소를 입력하세요."
    }
    val octets = uri.host?.split('.')?.map { it.toIntOrNull() } ?: emptyList()
    require(octets.size == 4 && octets.all { it != null && it in 0..255 }) { "카메라의 사설 IPv4 주소를 입력하세요." }
    val privateAddress = octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 172 && octets[1]!! in 16..31) || (octets[0] == 169 && octets[1] == 254)
    require(privateAddress && (uri.port == -1 || uri.port in 1..65535)) { "카메라의 사설 IPv4 주소와 포트를 확인하세요." }
    return uri
}
