package com.example.dronecontroller

import com.example.dronecontroller.control.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot

class ControlProtocolTest {
    @Test fun neutralRequestsHoldWithZeroVelocity() {
        val packet = ControlProtocol.packet(0, ControlInput())
        assertEquals(12, packet.size)
        assertArrayEquals(byteArrayOf(68, 67, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0), packet)
    }
    @Test fun bothSticksAreEncodedIndependentlyInLittleEndian() {
        val packet = ByteBuffer.wrap(ControlProtocol.packet(0x1234,
            ControlInput(Stick(-.5f, .75f), -.25f))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, packet.get(3).toInt())
        assertEquals(0x1234, packet.getShort(4).toInt())
        assertEquals(750, packet.getShort(6).toInt())
        assertEquals(-500, packet.getShort(8).toInt())
        assertEquals(-250, packet.getShort(10).toInt())
    }
    @Test fun emergencyDiscardsMovingInputsAndWrapsSequence() {
        val packet = ByteBuffer.wrap(ControlProtocol.packet(65536,
            ControlInput(Stick(1f, 1f), 1f), true)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, packet.get(3).toInt())
        assertEquals(0, packet.getShort(4).toInt())
        for (offset in listOf(6, 8, 10)) assertEquals(0, packet.getShort(offset).toInt())
    }
    @Test fun deadZoneAndNonFiniteInputProduceNeutral() {
        assertEquals(Stick(), normalizedStick(.02f, -.03f))
        assertEquals(Stick(), normalizedStick(Float.NaN, 1f))
        assertEquals(Stick(), normalizedStick(1f, Float.POSITIVE_INFINITY))
    }
    @Test fun diagonalTravelIsLimitedToUnitCircle() {
        val stick = normalizedStick(2f, 2f)
        assertEquals(1f, hypot(stick.x, stick.y), .0001f)
        assertEquals(stick.x, stick.y, .0001f)
    }
    @Test fun altitudeIgnoresHorizontalMotion() {
        assertEquals(Stick(0f, 1f), normalizedStick(100f, 1f, true))
        assertEquals(Stick(), normalizedStick(1f, 0f, true))
    }
    @Test fun invalidAxesNeverOverflowThePacket() {
        val bytes = ByteBuffer.wrap(ControlProtocol.packet(1,
            ControlInput(Stick(Float.NaN, 5f), -5f))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1000, bytes.getShort(6).toInt())
        assertEquals(0, bytes.getShort(8).toInt())
        assertEquals(-1000, bytes.getShort(10).toInt())
    }
}
