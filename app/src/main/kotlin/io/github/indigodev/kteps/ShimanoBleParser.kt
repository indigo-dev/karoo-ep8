package io.github.indigodev.kteps

import java.util.UUID

object ShimanoBleParser {

    val SERVICE_BATTERY: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val CHAR_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    val SERVICE_SHIMANO_DFLY: UUID = UUID.fromString("000018ef-5348-494d-414e-4f5f424c4500")
    val SERVICE_SHIMANO_AUTH: UUID = UUID.fromString("000018ff-5348-494d-414e-4f5f424c4500")
    val CHAR_SHIMANO_TELEMETRY: UUID = UUID.fromString("00002ac1-5348-494d-414e-4f5f424c4500")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val PACKET_REALTIME = 0x02
    private const val PACKET_REALTIME_MIN_LEN = 10

    private const val PACKET_ODOMETER_RANGE = 0x03
    private const val PACKET_ODOMETER_RANGE_MIN_LEN = 19

    private const val INVALID_UINT8 = 0xFF
    private const val INVALID_UINT16 = 0xFFFF
    private const val INVALID_UINT32 = 0xFFFF_FFFFL

    const val SHIMANO_COMPANY_ID = 0x044A
    private const val SHIMANO_UUID_SUFFIX = "-5348-494d-414e-4f5f424c4500"

    data class TelemetryParseResult(
        val telemetry: KtepsTelemetry,
        val sessionStartOdoMeters: Long?
    )

    fun isShimanoAdvertisement(
        deviceName: String?,
        serviceUuids: List<UUID>,
        manufacturerIds: Set<Int> = emptySet()
    ): Boolean {
        if (manufacturerIds.contains(SHIMANO_COMPANY_ID)) {
            return true
        }
        if (serviceUuids.any { it.toString().endsWith(SHIMANO_UUID_SUFFIX, ignoreCase = true) }) {
            return true
        }
        val name = deviceName?.trim().orEmpty()
        if (name.isEmpty()) return false
        return name.startsWith("EW", ignoreCase = true) ||
            name.startsWith("SC-E", ignoreCase = true) ||
            name.contains("SHIMANO", ignoreCase = true) ||
            name.contains("STEPS", ignoreCase = true) ||
            name.contains("EP8", ignoreCase = true) ||
            name.contains("EP6", ignoreCase = true)
    }

    fun parseBatteryLevel(bytes: ByteArray?): Int? {
        if (bytes == null || bytes.isEmpty()) return null
        return (bytes[0].toInt() and 0xFF).coerceIn(0, 100)
    }

    fun parseTelemetryPacket(
        bytes: ByteArray?,
        current: KtepsTelemetry,
        sessionStartOdoMeters: Long?
    ): TelemetryParseResult {
        if (bytes == null || bytes.isEmpty()) {
            return TelemetryParseResult(current, sessionStartOdoMeters)
        }

        return when (bytes[0].toInt() and 0xFF) {
            PACKET_REALTIME -> {
                if (bytes.size < PACKET_REALTIME_MIN_LEN) {
                    return TelemetryParseResult(current, sessionStartOdoMeters)
                }
                val mode = AssistMode.fromCode(bytes[1].toInt() and 0xFF)
                val rawSpeed = uInt16LE(bytes, 2)
                val speedKmh = if (rawSpeed != INVALID_UINT16) rawSpeed / 10.0 else 0.0
                val watts = bytes[4].toInt() and 0xFF
                val cadence = bytes[5].toInt() and 0xFF

                TelemetryParseResult(
                    telemetry = current.copy(
                        assistMode = mode,
                        speedKmh = speedKmh,
                        riderPowerWatts = if (watts != INVALID_UINT8) watts else 0,
                        cadenceRpm = if (cadence != INVALID_UINT8) cadence else 0
                    ),
                    sessionStartOdoMeters = sessionStartOdoMeters
                )
            }

            PACKET_ODOMETER_RANGE -> {
                if (bytes.size < PACKET_ODOMETER_RANGE_MIN_LEN) {
                    return TelemetryParseResult(current, sessionStartOdoMeters)
                }
                val rawOdoMeters = uInt32LE(bytes, 1)
                val hasValidOdo = rawOdoMeters != INVALID_UINT32
                val updatedStartMeters = if (hasValidOdo) {
                    sessionStartOdoMeters ?: rawOdoMeters
                } else {
                    sessionStartOdoMeters
                }
                val odoMeters = if (hasValidOdo) rawOdoMeters else current.odometerMeters
                val tripMeters = if (hasValidOdo && updatedStartMeters != null) {
                    (rawOdoMeters - updatedStartMeters).coerceAtLeast(0L)
                } else {
                    current.tripMeters
                }

                val boostKm = uInt16LE(bytes, 13)
                val trailKm = uInt16LE(bytes, 15)
                val ecoKm = uInt16LE(bytes, 17)

                TelemetryParseResult(
                    telemetry = current.copy(
                        odometerMeters = odoMeters,
                        tripMeters = tripMeters,
                        rangeBoostKm = if (boostKm != INVALID_UINT16) boostKm else current.rangeBoostKm,
                        rangeTrailKm = if (trailKm != INVALID_UINT16) trailKm else current.rangeTrailKm,
                        rangeEcoKm = if (ecoKm != INVALID_UINT16) ecoKm else current.rangeEcoKm
                    ),
                    sessionStartOdoMeters = updatedStartMeters
                )
            }

            else -> TelemetryParseResult(current, sessionStartOdoMeters)
        }
    }

    internal fun uInt16LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    internal fun uInt32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
