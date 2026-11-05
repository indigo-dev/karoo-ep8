package io.github.indigodev.kteps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ShimanoBleParserTest {

    @Test
    fun `parseBatteryLevel handles valid and out-of-range values`() {
        assertNull(ShimanoBleParser.parseBatteryLevel(null))
        assertNull(ShimanoBleParser.parseBatteryLevel(byteArrayOf()))
        assertEquals(0, ShimanoBleParser.parseBatteryLevel(byteArrayOf(0x00)))
        assertEquals(62, ShimanoBleParser.parseBatteryLevel(byteArrayOf(62)))
        assertEquals(100, ShimanoBleParser.parseBatteryLevel(byteArrayOf(100)))
        assertEquals(100, ShimanoBleParser.parseBatteryLevel(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `parseTelemetryPacket decodes packet 0x02 realtime telemetry`() {
        // Header=0x02, Mode=2 (TRAIL), Speed=254 (25.4 km/h -> 0xFE, 0x00), Watts=195, Cadence=84
        val packet = byteArrayOf(
            0x02,
            0x02,
            0xFE.toByte(), 0x00,
            195.toByte(),
            84,
            0x00, 0x00, 0x00, 0x00
        )

        val result = ShimanoBleParser.parseTelemetryPacket(
            bytes = packet,
            current = KtepsTelemetry(connected = true),
            sessionStartOdoMeters = null
        )

        assertEquals(AssistMode.TRAIL, result.telemetry.assistMode)
        assertEquals(25.4, result.telemetry.speedKmh, 0.001)
        assertEquals(195, result.telemetry.riderPowerWatts)
        assertEquals(84, result.telemetry.cadenceRpm)
    }

    @Test
    fun `parseTelemetryPacket handles invalid 0xFF and 0xFFFF sentinels in packet 0x02`() {
        val packet = byteArrayOf(
            0x02,
            0x05, // WALK+
            0xFF.toByte(), 0xFF.toByte(), // Invalid speed
            0xFF.toByte(),                // Invalid watts
            0xFF.toByte(),                // Invalid cadence
            0x00, 0x00, 0x00, 0x00
        )

        val result = ShimanoBleParser.parseTelemetryPacket(
            bytes = packet,
            current = KtepsTelemetry(connected = true, speedKmh = 18.2, riderPowerWatts = 150, cadenceRpm = 75),
            sessionStartOdoMeters = 10_000L
        )

        assertEquals(AssistMode.WALK_ACTIVE, result.telemetry.assistMode)
        assertEquals(0.0, result.telemetry.speedKmh, 0.001)
        assertEquals(0, result.telemetry.riderPowerWatts)
        assertEquals(0, result.telemetry.cadenceRpm)
        assertEquals(10_000L, result.sessionStartOdoMeters)
    }

    @Test
    fun `parseTelemetryPacket decodes packet 0x03 odometer, session trip, and ranges`() {
        // First 0x03 packet at 32,809 meters (0x00008029 LE -> 0x29, 0x80, 0x00, 0x00)
        // Boost=42km, Trail=68km, Eco=112km
        val firstPacket = ByteArray(19).apply {
            this[0] = 0x03
            this[1] = 0x29
            this[2] = 0x80.toByte()
            this[3] = 0x00
            this[4] = 0x00
            this[13] = 42; this[14] = 0
            this[15] = 68; this[16] = 0
            this[17] = 112; this[18] = 0
        }

        val firstResult = ShimanoBleParser.parseTelemetryPacket(
            bytes = firstPacket,
            current = KtepsTelemetry(connected = true, assistMode = AssistMode.TRAIL),
            sessionStartOdoMeters = null
        )

        assertEquals(32_809L, firstResult.sessionStartOdoMeters)
        assertEquals(32_809L, firstResult.telemetry.odometerMeters)
        assertEquals(0L, firstResult.telemetry.tripMeters)
        assertEquals(42, firstResult.telemetry.rangeBoostKm)
        assertEquals(68, firstResult.telemetry.rangeTrailKm)
        assertEquals(112, firstResult.telemetry.rangeEcoKm)
        assertEquals(68, firstResult.telemetry.activeRangeKm)

        // Second 0x03 packet after riding 2,500 meters -> 35,309 meters (0x000089ED LE -> 0xED, 0x89, 0x00, 0x00)
        val secondPacket = firstPacket.copyOf().apply {
            this[1] = 0xED.toByte()
            this[2] = 0x89.toByte()
            // Simulate temporary 0xFFFF range sentinel for Boost
            this[13] = 0xFF.toByte()
            this[14] = 0xFF.toByte()
        }

        val secondResult = ShimanoBleParser.parseTelemetryPacket(
            bytes = secondPacket,
            current = firstResult.telemetry,
            sessionStartOdoMeters = firstResult.sessionStartOdoMeters
        )

        assertEquals(32_809L, secondResult.sessionStartOdoMeters)
        assertEquals(35_309L, secondResult.telemetry.odometerMeters)
        assertEquals(2_500L, secondResult.telemetry.tripMeters)
        assertEquals(42, secondResult.telemetry.rangeBoostKm) // preserved previous valid value
    }

    @Test
    fun `fresh telemetry reports unknown values instead of zero`() {
        val initial = KtepsTelemetry()

        assertFalse(initial.connected)
        assertNull(initial.batteryPercent)
        assertNull(initial.assistMode)
        assertNull(initial.activeRangeKm)
        assertNull(initial.odometerMeters)
        assertNull(initial.tripMeters)

        // A 0x03 packet before any 0x02 packet: ranges known, active range still unknown
        val rangePacket = ByteArray(19).apply {
            this[0] = 0x03
            this[13] = 42
            this[15] = 68
            this[17] = 112
        }
        val withRanges = ShimanoBleParser.parseTelemetryPacket(rangePacket, initial, null).telemetry
        assertEquals(112, withRanges.rangeEcoKm)
        assertNull(withRanges.activeRangeKm)

        // Once the assist mode is known, the matching range becomes active
        assertEquals(42, withRanges.copy(assistMode = AssistMode.BOOST).activeRangeKm)
    }

    @Test
    fun `parseTelemetryPacket ignores truncated packets safely`() {
        val initial = KtepsTelemetry(connected = true, speedKmh = 21.5)
        val short02 = byteArrayOf(0x02, 0x01, 0x10)
        val short03 = ByteArray(10).apply { this[0] = 0x03 }

        assertEquals(initial, ShimanoBleParser.parseTelemetryPacket(short02, initial, null).telemetry)
        assertEquals(initial, ShimanoBleParser.parseTelemetryPacket(short03, initial, null).telemetry)
    }

    @Test
    fun `isShimanoAdvertisement matches UUIDs, manufacturer ID, and Shimano names`() {
        assertTrue(
            ShimanoBleParser.isShimanoAdvertisement(
                deviceName = "Custom Bike Name",
                serviceUuids = listOf(ShimanoBleParser.SERVICE_SHIMANO_AUTH)
            )
        )
        assertTrue(
            ShimanoBleParser.isShimanoAdvertisement(
                deviceName = "Renamed Orbea Rise",
                serviceUuids = listOf(UUID.fromString("000018fe-5348-494d-414e-4f5f424c4500"))
            )
        )
        assertTrue(
            ShimanoBleParser.isShimanoAdvertisement(
                deviceName = "My Canyon",
                serviceUuids = emptyList(),
                manufacturerIds = setOf(ShimanoBleParser.SHIMANO_COMPANY_ID)
            )
        )
        assertTrue(ShimanoBleParser.isShimanoAdvertisement("EW-EN100", emptyList()))
        assertTrue(ShimanoBleParser.isShimanoAdvertisement("SC-EM800", emptyList()))
        assertTrue(ShimanoBleParser.isShimanoAdvertisement("SC-EN600", emptyList()))
        assertTrue(ShimanoBleParser.isShimanoAdvertisement("Shimano STEPS", emptyList()))
        assertTrue(ShimanoBleParser.isShimanoAdvertisement("EP600", emptyList()))
        assertFalse(
            ShimanoBleParser.isShimanoAdvertisement(
                deviceName = "Garmin HRM-Pro",
                serviceUuids = listOf(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")),
                manufacturerIds = setOf(0x0087)
            )
        )
    }
}
