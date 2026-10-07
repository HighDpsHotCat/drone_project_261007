package com.example.dronecontroller.control

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.hypot

data class Stick(val x: Float = 0f, val y: Float = 0f)

/** +x = right, +y = forward/up. Radial dead zone, circular travel limit. */
fun normalizedStick(x: Float, y: Float, verticalOnly: Boolean = false): Stick {
    if (!x.isFinite() || !y.isFinite()) return Stick()
    val px = if (verticalOnly) 0f else x
    val length = hypot(px, y)
    if (length <= 0.08f) return Stick()
    val scale = ((length.coerceAtMost(1f) - 0.08f) / 0.92f) / length
    return Stick(px * scale, y * scale)
}

data class ControlInput(val horizontal: Stick = Stick(), val altitude: Float = 0f) {
    val neutral: Boolean get() = horizontal == Stick() && altitude == 0f
}

object ControlProtocol {
    // Nordic UART service. ESP32 firmware must implement the contract in docs/ESP32_PROTOCOL.md.
    val service: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val command: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val telemetry: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun packet(sequence: Int, input: ControlInput, emergency: Boolean = false): ByteArray {
        fun axis(value: Float): Short =
            ((if (value.isFinite()) value else 0f).coerceIn(-1f, 1f) * 1000).toInt().toShort()
        val safe = if (emergency) ControlInput() else input
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put('D'.code.toByte()).put('C'.code.toByte()).put(1)
            .put((if (emergency) 2 else if (safe.neutral) 0 else 1).toByte())
            .putShort((sequence and 0xffff).toShort())
            .putShort(axis(safe.horizontal.y)).putShort(axis(safe.horizontal.x))
            .putShort(axis(safe.altitude)).array()
    }
}
