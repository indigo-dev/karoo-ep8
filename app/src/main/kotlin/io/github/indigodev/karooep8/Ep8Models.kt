package io.github.indigodev.karooep8

enum class AssistMode(
    val code: Int,
    val label: String,
    val dotColorArgb: Int
) {
    OFF(0, "OFF", 0xFF9E9E9E.toInt()),
    ECO(1, "ECO", 0xFF2196F3.toInt()),
    TRAIL(2, "TRAIL", 0xFF4CAF50.toInt()),
    BOOST(3, "BOOST", 0xFFFFA000.toInt()),
    WALK(4, "WALK", 0xFF00BCD4.toInt()),
    WALK_ACTIVE(5, "WALK+", 0xFFE040FB.toInt());

    companion object {
        fun fromCode(code: Int): AssistMode =
            entries.firstOrNull { it.code == code } ?: OFF
    }
}

data class DiscoveredShimanoBike(
    val macAddress: String,
    val displayName: String
)

/**
 * Latest telemetry snapshot of the connected Shimano drive unit.
 *
 * [connected] is only `true` once the GATT handshake has completed (services discovered and
 * telemetry notifications enabled), i.e. when data can actually be expected.
 *
 * Nullable values are `null` until the first valid value has been received from the bike,
 * so "unknown" is never confused with a real `0` (e.g. 0 % battery).
 */
data class Ep8Telemetry(
    val connected: Boolean = false,
    val batteryPercent: Int? = null,
    val assistMode: AssistMode? = null,
    val speedKmh: Double = 0.0,
    val riderPowerWatts: Int = 0,
    val cadenceRpm: Int = 0,
    val rangeBoostKm: Int? = null,
    val rangeTrailKm: Int? = null,
    val rangeEcoKm: Int? = null,
    val odometerMeters: Long? = null,
    val tripMeters: Long? = null
) {
    val activeRangeKm: Int?
        get() = when (assistMode) {
            null -> null
            AssistMode.ECO -> rangeEcoKm
            AssistMode.TRAIL -> rangeTrailKm
            AssistMode.BOOST -> rangeBoostKm
            else -> rangeEcoKm
        }
}
